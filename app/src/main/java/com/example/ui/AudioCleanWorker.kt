package com.example.ui

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.rikorose.deepfilternet.NativeDeepFilterNet

class AudioCleanWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val inputUriString = inputData.getString("inputUri") 
            ?: return Result.failure(workDataOf("error" to "MISSING_INPUT_URI"))
        
        val inputUri = Uri.parse(inputUriString)
        
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var deepFilterNet: NativeDeepFilterNet? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        var isMuxerStarted = false
        var samplesWritten = 0L
        
        val cacheDir = applicationContext.cacheDir
        val outputFile = File(cacheDir, "DEEPFILTERNET_CLEANED_${System.currentTimeMillis()}.m4a")
        val tempWavFile = File(cacheDir, "DEEPFILTERNET_CLEANED_${System.currentTimeMillis()}.wav")
        var wavFileOutputStream: java.io.FileOutputStream? = null
        
        try {
            // 1. Initialize Native DeepFilterNet AI Engine
            val dfn = NativeDeepFilterNet(applicationContext)
            var initRetries = 0
            while (dfn.frameLength <= 0 && initRetries < 200) {
                kotlinx.coroutines.delay(20)
                initRetries++
            }
            if (dfn.frameLength <= 0) {
                android.util.Log.e("SIGNAL_ERR", "NativeDeepFilterNet initialization timed out. frameLength is still ${dfn.frameLength}")
                return Result.failure(workDataOf("error" to "MODEL_INITIALIZATION_TIMEOUT"))
            }

            dfn.setAttenuationLimit(30f)
            deepFilterNet = dfn
            val frameLength = dfn.frameLength.toInt()
            val frameSamplesCount = frameLength / 2
            val aiBuffer = ByteBuffer.allocateDirect(frameLength).apply {
                order(ByteOrder.LITTLE_ENDIAN)
            }


            // 2. Setup MediaExtractor
            extractor = MediaExtractor()
            extractor.setDataSource(applicationContext, inputUri, null)
            
            var audioTrackIdx = -1
            var audioFormat: MediaFormat? = null
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIdx = i
                    audioFormat = format
                    break
                }
            }
            
            if (audioTrackIdx == -1 || audioFormat == null) {
                return Result.failure(workDataOf("error" to "NO_AUDIO_TRACK_FOUND"))
            }
            
            extractor.selectTrack(audioTrackIdx)
            
            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            } else {
                1L
            }
            
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val inputSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
            } else {
                48000
            }
            val inputChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
                1
            }

            // 3. Configure Decoder
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()

            // 4. Configure Encoder for AAC (48kHz, Mono)
            val encoderFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_SAMPLE_RATE, 48000)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            }
            
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            // 5. Initialize MediaMuxer
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrackIndex = -1
            isMuxerStarted = false
            
            // Open fallback WAV stream and write a dummy 44-byte header
            try {
                wavFileOutputStream = java.io.FileOutputStream(tempWavFile)
                wavFileOutputStream!!.write(ByteArray(44))
            } catch (e: Exception) {
                android.util.Log.e("SIGNAL_WAV", "Error initializing fallback WAV file", e)
            }

            // 6. Processing Buffers & Accumulator
            val timeoutUs = 5000L
            val pcmAccumulator = ArrayDeque<Short>()
            
            val decBufferInfo = MediaCodec.BufferInfo()
            val encBufferInfo = MediaCodec.BufferInfo()
            
            var isDecoderInputEOS = false
            var isDecoderOutputEOS = false
            var isEncoderInputEOS = false
            var isEncoderOutputEOS = false
            
            var originalSamplesDecoded = 0L
            var originalSamplesEncoded = 0L
            var hasPaddedEnd = false
            val processedPcmAccumulator = ArrayDeque<Short>()
            
            var maxProgressPercent = 0
            
            // Safety Tracking: Whenever any codec consumes inputs or produces outputs,
            // we refresh this timestamp. If there is ZERO work done across the entire loop
            // for more than 4.0 seconds, we break out forcefully so we do not spin forever.
            var lastActiveStateTime = System.currentTimeMillis()
            val inactivityFailsafeBoundaryMs = 4000L

            android.util.Log.w("SIGNAL_EOS", "Initiating modern state-machine architecture for MediaCodec processing loop.")

            while (!isEncoderOutputEOS) {
                var workDoneInIteration = false
                val iterationTime = System.currentTimeMillis()

                // 1. Hardware/Pipeline Failsafe check (Only active during drain phase after Decoder EOS to prevent killing setup)
                if (isDecoderOutputEOS) {
                    if (iterationTime - lastActiveStateTime > inactivityFailsafeBoundaryMs) {
                        android.util.Log.w("SIGNAL_EOS", "SAFETY FAILURE WARNING: The media pipeline has stalled for over " + 
                            "${iterationTime - lastActiveStateTime}ms during drain. Activating failsafe termination.")
                        break
                    }
                }

                // 2. DECODER INPUT: Feed compressed source to decoder
                if (!isDecoderInputEOS) {
                    val inputBufIdx = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputBufIdx >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputBufIdx)!!
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isDecoderInputEOS = true
                            workDoneInIteration = true
                            android.util.Log.w("SIGNAL_EOS", "MediaExtractor EOF reached. Signaled decoder input stream EOS.")
                        } else {
                            val sampleTimeUs = extractor.sampleTime
                            if (sampleTimeUs >= 0 && durationUs > 0) {
                                val progressPercent = ((sampleTimeUs.toFloat() / durationUs.toFloat()) * 99f)
                                    .coerceIn(0f, 99f).toInt()
                                if (progressPercent > maxProgressPercent) {
                                    maxProgressPercent = progressPercent
                                    setProgress(workDataOf("progress" to maxProgressPercent))
                                }
                            }
                            decoder.queueInputBuffer(inputBufIdx, 0, sampleSize, sampleTimeUs, 0)
                            extractor.advance()
                            workDoneInIteration = true
                        }
                    }
                }

                // 3. DECODER OUTPUT: Retrieve raw synthesized PCM from decoder
                if (!isDecoderOutputEOS) {
                    val decompressedBufIdx = decoder.dequeueOutputBuffer(decBufferInfo, timeoutUs)
                    if (decompressedBufIdx >= 0) {
                        val decodedBytes = decoder.getOutputBuffer(decompressedBufIdx)
                        if (decodedBytes != null && decBufferInfo.size > 0) {
                            val convertedPCM = downmixAndResample(
                                decodedBytes = decodedBytes,
                                info = decBufferInfo,
                                inputChannels = inputChannels,
                                inputSampleRate = inputSampleRate
                            )
                            originalSamplesDecoded += convertedPCM.size
                            for (s in convertedPCM) {
                                pcmAccumulator.add(s)
                            }
                            
                            val decodedPts = decBufferInfo.presentationTimeUs
                            if (decodedPts > 0) {
                                val progressPercent = ((decodedPts.toFloat() / durationUs.toFloat()) * 99f)
                                    .coerceIn(0f, 99f).toInt()
                                if (progressPercent > maxProgressPercent) {
                                    maxProgressPercent = progressPercent
                                    setProgress(workDataOf("progress" to maxProgressPercent))
                                }
                            }
                            workDoneInIteration = true
                        }
                        decoder.releaseOutputBuffer(decompressedBufIdx, false)
                        
                        if (originalSamplesDecoded % 48000 < 4000) {
                            android.util.Log.w("SIGNAL_DEBUG", "Progress -> Decoded: $originalSamplesDecoded, Encoded: $originalSamplesEncoded, pcmAccumulator: ${pcmAccumulator.size}, processed: ${processedPcmAccumulator.size}")
                        }

                        if ((decBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isDecoderOutputEOS = true
                            workDoneInIteration = true
                            android.util.Log.w("SIGNAL_EOS", "Decoder outputs EOF element successfully. Total Decoded: $originalSamplesDecoded")
                        }
                    } else if (decompressedBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        workDoneInIteration = true
                    }
                }

                // 4. DSP NOISE GATING WITH NEURAL NET (NativeDeepFilterNet)
                try {
                    val samplesToExtract = frameSamplesCount
                    if (pcmAccumulator.size >= samplesToExtract || (isDecoderOutputEOS && pcmAccumulator.isNotEmpty() && !hasPaddedEnd)) {
                        aiBuffer.clear()
                        var shortsInChunk = 0
                        while (shortsInChunk < samplesToExtract) {
                            if (pcmAccumulator.isNotEmpty()) {
                                aiBuffer.putShort(pcmAccumulator.removeFirst())
                            } else {
                                aiBuffer.putShort(0)
                            }
                            shortsInChunk++
                        }
                        
                        if (isDecoderOutputEOS && pcmAccumulator.isEmpty()) {
                            hasPaddedEnd = true
                        }
                        
                        aiBuffer.position(0)
                        aiBuffer.limit(frameLength)
                        
                        // Process frame in-place using NativeDeepFilterNet
                        deepFilterNet?.processFrame(aiBuffer)
                        
                        aiBuffer.position(0)
                        val processedShorts = ShortArray(samplesToExtract)
                        aiBuffer.asShortBuffer().get(processedShorts)
                        
                        for (s in processedShorts) {
                            processedPcmAccumulator.add(s)
                        }
                        workDoneInIteration = true
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AudioCleanWorker", "Error processing AI frame filtering block", e)
                    pcmAccumulator.clear()
                }

                // 5. ENCODER INPUT: Feed processed clean mono PCM into AAC encoder
                if (!isEncoderInputEOS) {
                    val inputBufIdx = encoder.dequeueInputBuffer(timeoutUs)
                    if (inputBufIdx >= 0) {
                        val inputBuf = encoder.getInputBuffer(inputBufIdx)!!
                        inputBuf.clear()
                        
                        val shortsToWrite = minOf(inputBuf.remaining() / 2, processedPcmAccumulator.size)
                        if (shortsToWrite > 0) {
                            val tempBytes = ByteArray(shortsToWrite * 2)
                            val byteBufWrap = ByteBuffer.wrap(tempBytes).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until shortsToWrite) {
                                byteBufWrap.putShort(processedPcmAccumulator.removeFirst())
                            }
                            inputBuf.put(tempBytes)
                            
                            // Write raw PCM bytes to WAV file
                            try {
                                wavFileOutputStream?.write(tempBytes)
                            } catch (e: Exception) {
                                android.util.Log.e("SIGNAL_WAV", "Error writing to fallback WAV file", e)
                            }
                            
                            val ptsUs = (originalSamplesEncoded * 1_000_000L) / 48000L
                            encoder.queueInputBuffer(inputBufIdx, 0, shortsToWrite * 2, ptsUs, 0)
                            originalSamplesEncoded += shortsToWrite
                            workDoneInIteration = true
                        } else if (isDecoderOutputEOS && pcmAccumulator.size < frameSamplesCount && processedPcmAccumulator.isEmpty()) {
                            // Signal EOS to encoder only when input decoder is dry and all processed samples are fully drained
                            encoder.queueInputBuffer(inputBufIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEncoderInputEOS = true
                            workDoneInIteration = true
                            android.util.Log.w("SIGNAL_EOS", "All synthesized DSP blocks written. Encoding EOS signaled.")
                        }
                    }
                }

                // 6. ENCODER OUTPUT: Retrieve compressed bitstream and write to MP4 via MediaMuxer
                if (!isEncoderOutputEOS) {
                    val compressedBufIdx = encoder.dequeueOutputBuffer(encBufferInfo, timeoutUs)
                    if (compressedBufIdx >= 0) {
                        val outputBuf = encoder.getOutputBuffer(compressedBufIdx)!!
                        
                        // CRITICAL FIX 1: Ignore CODEC_CONFIG buffers (they are already in the track format)
                        val isCodecConfig = (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        
                        // CRITICAL FIX 2: Ensure timestamp is valid
                        val isValidTime = encBufferInfo.presentationTimeUs >= 0

                        // Safe writing: Only write if muxer has started, we have valid payload size, not a config block, and a valid timestamp
                        if (isMuxerStarted && encBufferInfo.size > 0 && !isCodecConfig && isValidTime) {
                            muxer.writeSampleData(muxerTrackIndex, outputBuf, encBufferInfo)
                            samplesWritten++
                        }
                        encoder.releaseOutputBuffer(compressedBufIdx, false)
                        workDoneInIteration = true
                        
                        if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isEncoderOutputEOS = true
                            android.util.Log.w("SIGNAL_EOS", "Encoder signaled output EOS. Clean audio track finished.")
                        }
                    } else if (compressedBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Immediately fetch and configure track format from active codec format
                        val newFormat = encoder.outputFormat
                        muxerTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        isMuxerStarted = true
                        workDoneInIteration = true
                        android.util.Log.w("SIGNAL_EOS", "MediaMuxer metadata initialized and track started with active profile config.")
                    }
                }

                // Refresh safety timer if we of downstream codec executed work,
                // otherwise yield threads cooperatively to prevent pipeline starvation
                if (workDoneInIteration) {
                    lastActiveStateTime = iterationTime
                } else {
                    kotlinx.coroutines.delay(5)
                }
            }

            setProgress(workDataOf("progress" to 100))
            return Result.success(workDataOf(
                "outputUri" to Uri.fromFile(outputFile).toString(),
                "wavUri" to Uri.fromFile(tempWavFile).toString()
            ))

        } catch (e: Exception) {
            e.printStackTrace()
            return Result.failure(workDataOf("error" to (e.localizedMessage ?: "PROCESSING_PIPELINE_FAULT")))
        } finally {
            try {
                wavFileOutputStream?.close()
                wavFileOutputStream = null
                
                // Write finalized WAV header to fallback file
                if (tempWavFile.exists() && tempWavFile.length() >= 44) {
                    val rawAudioSize = tempWavFile.length() - 44
                    java.io.RandomAccessFile(tempWavFile, "rw").use { raf ->
                        raf.seek(0)
                        val outStream = object : java.io.OutputStream() {
                            override fun write(b: Int) {
                                raf.write(b)
                            }
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                raf.write(b, off, len)
                            }
                        }
                        writeWavHeader(outStream, rawAudioSize, sampleRate = 48000, channels = 1, bitsPerSample = 16)
                    }
                    android.util.Log.w("SIGNAL_WAV", "Successfully finalized WAV header with audio size: $rawAudioSize")
                }
            } catch (ex: Exception) {
                android.util.Log.e("SIGNAL_WAV", "Error finalizing WAV header", ex)
            }

            try {
                if (isMuxerStarted && samplesWritten > 0) {
                    android.util.Log.w("SIGNAL_MUXER", "Stopping MediaMuxer after writing $samplesWritten samples.")
                    muxer?.stop() // This writes the crucial 'moov' atom
                }
            } catch (e: Exception) {
                android.util.Log.e("SIGNAL_MUXER", "Error stopping muxer", e)
            } finally {
                try { deepFilterNet?.release() } catch (e: Exception) {}
                try { muxer?.release() } catch (e: Exception) {}
                try { encoder?.release() } catch (e: Exception) {}
                try { decoder?.release() } catch (e: Exception) {}
                try { extractor?.release() } catch (e: Exception) {}
                try { pfd?.close() } catch (e: Exception) {}
            }
        }
    }

    private fun writeWavHeader(
        outputStream: java.io.OutputStream,
        totalDataSize: Long,
        sampleRate: Int = 48000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ) {
        val totalAudioLen = totalDataSize
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF/WAVE header
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toShort().toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte() // block align
        header[33] = 0
        header[34] = bitsPerSample.toByte() // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        outputStream.write(header, 0, 44)
    }

    private fun downmixAndResample(
        decodedBytes: ByteBuffer,
        info: MediaCodec.BufferInfo,
        inputChannels: Int,
        inputSampleRate: Int,
        outputSampleRate: Int = 48000
    ): ShortArray {
        decodedBytes.position(info.offset)
        decodedBytes.limit(info.offset + info.size)
        val shortBuf = decodedBytes.asShortBuffer()
        val rawSamplesCount = shortBuf.remaining()
        val originalShorts = ShortArray(rawSamplesCount)
        shortBuf.get(originalShorts)

        // 1. Downmix to Mono PCM
        val monoSamples = if (inputChannels == 1) {
            originalShorts
        } else {
            val count = rawSamplesCount / inputChannels
            val mono = ShortArray(count)
            for (i in 0 until count) {
                var sum = 0
                for (c in 0 until inputChannels) {
                    sum += originalShorts[i * inputChannels + c]
                }
                mono[i] = (sum / inputChannels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            mono
        }

        // 2. Resample to 48000 Hz if necessary
        if (inputSampleRate == outputSampleRate) {
            return monoSamples
        }

        val resampledCount = (monoSamples.size.toLong() * outputSampleRate / inputSampleRate).toInt()
        val resampled = ShortArray(resampledCount)
        for (i in 0 until resampledCount) {
            val origIndexFloat = i.toFloat() * inputSampleRate / outputSampleRate
            val index = origIndexFloat.toInt()
            val fraction = origIndexFloat - index
            if (index + 1 < monoSamples.size) {
                val s0 = monoSamples[index].toFloat()
                val s1 = monoSamples[index + 1].toFloat()
                resampled[i] = (s0 + fraction * (s1 - s0)).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            } else if (index < monoSamples.size) {
                resampled[i] = monoSamples[index]
            }
        }
        return resampled
    }
}

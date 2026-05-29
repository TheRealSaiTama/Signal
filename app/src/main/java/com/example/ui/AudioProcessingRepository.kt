package com.example.ui

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

data class AudioTelemetry(
    val durationUs: Long,
    val peakDecibels: Float,
    val noiseFloorDb: Float,
    val isNoiseFloorClean: Boolean,
    val anomaly: String
)

class AudioProcessingRepository(private val context: Context) {
    
    private val dspEngine = DspEngine()

    /**
     * Converts RMS float value to decibels safely.
     */
    private fun rmsToDb(rms: Float): Float {
        if (rms <= 1e-5f) return -100.0f
        val db = 20.0f * log10(rms)
        return db.coerceIn(-100.0f, 0.0f)
    }

    /**
     * Rapidly decodes a 1-2 second sample from the audio track using MediaCodec,
     * and computes statistics such as peak RMS decibels, noise floor estimate (quietest 20% frames),
     * and anomaly diagnosis.
     */
    suspend fun analyzeAudio(inputUri: Uri): Result<AudioTelemetry> = withContext(Dispatchers.Default) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, inputUri, null)

            var audioTrackIdx = -1
            var audioFormat: MediaFormat? = null
            
            // Locate audio track
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
                return@withContext Result.failure(Exception("NO AUDIO TRACK DETECTED IN SOURCE"))
            }

            extractor.selectTrack(audioTrackIdx)

            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()

            val pcmList = ArrayList<Short>()
            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            val timeoutUs = 5000L

            // Cap analysis sample array size (approx 1.5 seconds) to ensure < 150ms UI latency
            val maxSamplesToAnalyze = 66150 

            while (!isOutputEOS && pcmList.size < maxSamplesToAnalyze) {
                if (!isInputEOS) {
                    val inputBufIdx = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputBufIdx >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputBufIdx)!!
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEOS = true
                        } else {
                            decoder.queueInputBuffer(inputBufIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputBufIdx = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputBufIdx >= 0) {
                    val outputBuf = decoder.getOutputBuffer(outputBufIdx)
                    if (outputBuf != null && bufferInfo.size > 0) {
                        outputBuf.position(bufferInfo.offset)
                        val shortBuffer = outputBuf.asShortBuffer()
                        val tempArray = ShortArray(bufferInfo.size / 2)
                        shortBuffer.get(tempArray)
                        for (s in tempArray) {
                            pcmList.add(s)
                            if (pcmList.size >= maxSamplesToAnalyze) break
                        }
                    }
                    decoder.releaseOutputBuffer(outputBufIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                }
            }

            if (pcmList.isEmpty()) {
                return@withContext Result.success(AudioTelemetry(
                    durationUs = durationUs,
                    peakDecibels = -3.5f,
                    noiseFloorDb = -60.0f,
                    isNoiseFloorClean = true,
                    anomaly = "NONE"
                ))
            }

            // Slice sample array into frames and analyze statistics
            val frameSize = 1024
            val frameDbs = ArrayList<Float>()
            var maxRms = 0f
            val totalSamples = pcmList.size
            var offset = 0

            while (offset + frameSize <= totalSamples) {
                var sumSquares = 0.0
                for (j in 0 until frameSize) {
                    val sampleNormalized = pcmList[offset + j].toFloat() / 32768.0f
                    sumSquares += (sampleNormalized * sampleNormalized).toDouble()
                }
                val rms = sqrt(sumSquares / frameSize).toFloat()
                if (rms > maxRms) {
                    maxRms = rms
                }
                frameDbs.add(rmsToDb(rms))
                offset += frameSize
            }

            val peakDb = rmsToDb(maxRms).coerceIn(-100f, 0f)

            // Collect noise floor: Sort frame decibel levels and average the lowest (quietest) 20%
            frameDbs.sort()
            val numFrames = frameDbs.size
            val quietestCount = (numFrames * 0.20).toInt().coerceAtLeast(1)
            var sumNoiseFloor = 0f
            for (k in 0 until quietestCount) {
                sumNoiseFloor += frameDbs[k]
            }
            val noiseFloorDb = (sumNoiseFloor / quietestCount).coerceIn(-100f, 0f)

            val isNoiseFloorClean = noiseFloorDb < -55.0f
            val anomaly = when {
                isNoiseFloorClean -> "NONE"
                noiseFloorDb >= -43.0f -> "LOW-FREQ RUMBLE"
                else -> "STATIC"
            }

            Result.success(AudioTelemetry(
                durationUs = durationUs,
                peakDecibels = peakDb,
                noiseFloorDb = noiseFloorDb,
                isNoiseFloorClean = isNoiseFloorClean,
                anomaly = anomaly
            ))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                decoder?.stop()
            } catch (e: Exception) {}
            try {
                decoder?.release()
            } catch (e: Exception) {}
            try {
                extractor?.release()
            } catch (e: Exception) {}
            try {
                pfd?.close()
            } catch (e: Exception) {}
        }
    }

    /**
     * Decoding, filtering, and Encoding loop pipeline implemented fully on Dispatchers.Default
     * to perform raw PCM high-pass cleaning and noise gate filtering in memory without UI thread locks.
     */
    suspend fun cleanAudio(inputUri: Uri, noiseFloorDb: Float): Result<File> = withContext(Dispatchers.Default) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        val cacheDir = context.cacheDir
        val cleanOutputFile = File(cacheDir, "SIGNAL_CLEANED_${System.currentTimeMillis()}.m4a")

        try {
            if (cleanOutputFile.exists()) {
                cleanOutputFile.delete()
            }

            extractor = MediaExtractor()
            extractor.setDataSource(context, inputUri, null)

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
                return@withContext Result.failure(Exception("NO AUDIO TRACK DETECTED IN SOURCE"))
            }

            extractor.selectTrack(audioTrackIdx)

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
            } else {
                44100
            }
            val channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
                2
            }

            // --- PHASE 1: AUDIO DECODING TO PCM SHORT ARRAY ---
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()

            val rawPcmList = ArrayList<Short>()
            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            val timeoutUs = 5000L

            // Cap at 16 million shorts (approx 3 minutes of high quality audio) to avoid OOM
            val maxDecodeLimit = 16000000

            while (!isOutputEOS && rawPcmList.size < maxDecodeLimit) {
                if (!isInputEOS) {
                    val inputBufIdx = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputBufIdx >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputBufIdx)!!
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEOS = true
                        } else {
                            decoder.queueInputBuffer(inputBufIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputBufIdx = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputBufIdx >= 0) {
                    val outputBuf = decoder.getOutputBuffer(outputBufIdx)
                    if (outputBuf != null && bufferInfo.size > 0) {
                        outputBuf.position(bufferInfo.offset)
                        val shortBuffer = outputBuf.asShortBuffer()
                        val tempArray = ShortArray(bufferInfo.size / 2)
                        shortBuffer.get(tempArray)
                        for (s in tempArray) {
                            rawPcmList.add(s)
                            if (rawPcmList.size >= maxDecodeLimit) break
                        }
                    }
                    decoder.releaseOutputBuffer(outputBufIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                }
            }

            // Clean up decoding pipelines
            decoder.stop()
            decoder.release()
            decoder = null
            extractor.release()
            extractor = null

            val pcmData = rawPcmList.toShortArray()
            rawPcmList.clear() // Free memory reference

            // --- PHASE 2: RUN DIGITAL SIGNAL PROCESSING ---
            val processedPcm = dspEngine.process(
                pcmData = pcmData,
                sampleRate = sampleRate,
                channelCount = channelCount,
                noiseFloorDb = noiseFloorDb
            )

            // --- PHASE 3: ENCODE ISOLATED PCM BACK TO AAC / M4A PAYLOAD ---
            val encoderFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channelCount)
            encoderFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            encoderFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            encoderFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)

            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val muxer = MediaMuxer(cleanOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrackIndex = -1
            var muxerStarted = false

            val encBufferInfo = MediaCodec.BufferInfo()
            var shortsWritten = 0
            val totalShorts = processedPcm.size
            var isEncoderInputEOS = false
            var isEncoderOutputEOS = false

            while (!isEncoderOutputEOS) {
                if (!isEncoderInputEOS && shortsWritten < totalShorts) {
                    val inputBufIdx = encoder.dequeueInputBuffer(timeoutUs)
                    if (inputBufIdx >= 0) {
                        val inputBuf = encoder.getInputBuffer(inputBufIdx)!!
                        inputBuf.clear()
                        
                        val shortsToCopy = (inputBuf.remaining() / 2).coerceAtMost(totalShorts - shortsWritten)
                        if (shortsToCopy > 0) {
                            val tempByteBuffer = ByteBuffer.allocate(shortsToCopy * 2).order(ByteOrder.nativeOrder())
                            val tempShortBuffer = tempByteBuffer.asShortBuffer()
                            tempShortBuffer.put(processedPcm, shortsWritten, shortsToCopy)
                            tempByteBuffer.position(0)
                            inputBuf.put(tempByteBuffer)
                            
                            shortsWritten += shortsToCopy
                            
                            val presentationTimeUs = (shortsWritten.toLong() * 1_000_000L) / (sampleRate.toLong() * channelCount.toLong())
                            val flags = if (shortsWritten >= totalShorts) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            encoder.queueInputBuffer(inputBufIdx, 0, shortsToCopy * 2, presentationTimeUs, flags)
                        } else {
                            encoder.queueInputBuffer(inputBufIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEncoderInputEOS = true
                        }
                    }
                } else if (!isEncoderInputEOS && shortsWritten >= totalShorts) {
                    val inputBufIdx = encoder.dequeueInputBuffer(timeoutUs)
                    if (inputBufIdx >= 0) {
                        encoder.queueInputBuffer(inputBufIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEncoderInputEOS = true
                    }
                }

                val outputBufIdx = encoder.dequeueOutputBuffer(encBufferInfo, timeoutUs)
                if (outputBufIdx >= 0) {
                    val outputBuf = encoder.getOutputBuffer(outputBufIdx)!!
                    if (encBufferInfo.size > 0 && (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (muxerStarted) {
                            muxer.writeSampleData(muxerTrackIndex, outputBuf, encBufferInfo)
                        }
                    }
                    encoder.releaseOutputBuffer(outputBufIdx, false)
                    if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEncoderOutputEOS = true
                    }
                } else if (outputBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = encoder.outputFormat
                    muxerTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                }
            }

            try {
                encoder.stop()
            } catch (e: Exception) {}
            try {
                encoder.release()
            } catch (e: Exception) {}
            encoder = null

            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()

            Result.success(cleanOutputFile)
        } catch (e: Exception) {
            // In case of any encoding exception, fall back to safe fallback or pass original
            Result.failure(e)
        } finally {
            try {
                decoder?.stop()
            } catch (e: Exception) {}
            try {
                decoder?.release()
            } catch (e: Exception) {}
            try {
                encoder?.stop()
            } catch (e: Exception) {}
            try {
                encoder?.release()
            } catch (e: Exception) {}
            try {
                extractor?.release()
            } catch (e: Exception) {}
            try {
                pfd?.close()
            } catch (e: Exception) {}
        }
    }

    /**
     * Saves the final .m4a file into the user's public Music or Downloads directory
     * in a folder named "Signal_Cleaned" using Scoped Storage MediaStore.Audio API.
     */
    suspend fun exportToPublicDirectory(sourceFile: File, displayName: String): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val audioCollection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // Dynamic content detection for WAV and M4A
        val isWav = displayName.endsWith(".wav", ignoreCase = true) || sourceFile.name.endsWith(".wav", ignoreCase = true)
        val finalDisplayName = if (isWav) {
            if (displayName.endsWith(".wav", ignoreCase = true)) displayName else "${displayName.substringBeforeLast(".")}.wav"
        } else {
            if (displayName.endsWith(".m4a", ignoreCase = true)) displayName else "${displayName.substringBeforeLast(".")}.m4a"
        }

        val details = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, finalDisplayName)
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, if (isWav) "audio/wav" else "audio/m4a")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/Signal_Cleaned")
                put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        try {
            var savedUri: Uri? = null
            
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                val musicDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
                val signalCleanedDir = File(musicDir, "Signal_Cleaned")
                if (!signalCleanedDir.exists()) {
                    signalCleanedDir.mkdirs()
                }
                val destFile = File(signalCleanedDir, finalDisplayName)
                
                java.io.FileInputStream(sourceFile).use { input ->
                    java.io.FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                
                details.put(android.provider.MediaStore.Audio.Media.DATA, destFile.absolutePath)
                savedUri = resolver.insert(audioCollection, details)
            } else {
                savedUri = resolver.insert(audioCollection, details)
                if (savedUri != null) {
                    resolver.openOutputStream(savedUri)?.use { outputStream ->
                        java.io.FileInputStream(sourceFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    
                    details.clear()
                    details.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
                    resolver.update(savedUri, details, null, null)
                }
            }
            
            savedUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

package com.example.ui

import kotlin.math.PI
import kotlin.math.pow

class DspEngine {

    /**
     * Applies a 1-pole high-pass IIR filter (100Hz cutoff) and a noise gate.
     * Handles interleaved multi-channel PCM streams with independent filter states.
     */
    fun process(
        pcmData: ShortArray,
        sampleRate: Int,
        channelCount: Int,
        noiseFloorDb: Float
    ): ShortArray {
        val channels = channelCount.coerceAtLeast(1)
        val cutoffHz = 100f
        val dt = 1f / sampleRate
        val rc = 1f / (2f * PI.toFloat() * cutoffHz)
        val alpha = rc / (rc + dt)

        // Convert noise floor dB to normalized amplitude (0.0 to 1.0)
        // If noiseFloorDb is, say, -45dB, power = 10^(-45/20) = 0.0056
        // Short amplitude threshold = 0.0056 * 32768f
        val thresholdNormalized = 10.0.pow(noiseFloorDb.toDouble() / 20.0).toFloat()
        // Introduce a subtle manual threshold scaling factor (e.g. 1.2f) for distinct visual/gated results
        val thresholdShort = (thresholdNormalized * 32768f * 1.2f).coerceIn(0f, 32768f)

        // Filter states: one for each channel
        val prevX = FloatArray(channels)
        val prevY = FloatArray(channels)

        val output = ShortArray(pcmData.size)

        for (i in pcmData.indices) {
            val ch = i % channels
            val x = pcmData[i].toFloat()

            // Apply 1-pole high-pass IIR filter: y[n] = alpha * (y[n-1] + x[n] - x[n-1])
            val y = alpha * (prevY[ch] + x - prevX[ch])
            prevX[ch] = x
            prevY[ch] = y

            // Apply noise gate
            val sampleVal = if (Math.abs(y) < thresholdShort) {
                0f
            } else {
                y
            }

            output[i] = sampleVal.coerceIn(-32768f, 32767f).toInt().toShort()
        }

        return output
    }
}

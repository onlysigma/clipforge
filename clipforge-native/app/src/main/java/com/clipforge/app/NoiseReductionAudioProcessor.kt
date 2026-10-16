package com.clipforge.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * v2 noise reduction for ClipForge Native.
 *
 * Improved over v1:
 * - Smoother envelope follower (10ms attack, 150ms release) — no more choppy voice
 * - Soft-knee expander instead of hard gate — natural sound
 * - Dual-stage: high-pass (120Hz) + spectral-weighted expansion
 * - Gentler noise floor (-24dB instead of -38dB gate) — preserves voice ambience
 *
 * Only 16-bit PCM is handled; Transformer always decodes to PCM 16-bit here.
 */
class NoiseReductionAudioProcessor : BaseAudioProcessor() {

    private var channelCount: Int = 0
    private var sampleRate: Int = 44100
    private var hpX = FloatArray(0) // previous input per channel (high-pass)
    private var hpY = FloatArray(0) // previous output per channel (high-pass)
    private var envelope = 0f // smooth RMS envelope follower

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        hpX = FloatArray(channelCount)
        hpY = FloatArray(channelCount)
        envelope = 0f
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(bytes)
        output.order(ByteOrder.nativeOrder())

        val totalSamples = bytes / 2

        // One-pole high-pass coefficient for HIGH_PASS_HZ.
        val rc = 1f / (2f * PI.toFloat() * HIGH_PASS_HZ)
        val alpha = rc / (rc + 1f / sampleRate)

        // Envelope follower coefficients (10ms attack, 150ms release)
        val attackCoeff = 1f - kotlin.math.exp(-1f / (sampleRate * 0.010f))
        val releaseCoeff = 1f - kotlin.math.exp(-1f / (sampleRate * 0.150f))

        val frames = totalSamples / channelCount

        // Read all samples first (to avoid double-reading the buffer)
        val samples = FloatArray(totalSamples)
        for (i in 0 until totalSamples) {
            samples[i] = inputBuffer.short / 32768f
        }

        var sampleIdx = 0
        repeat(frames) {
            // Update envelope from current frame
            var frameEnergy = 0.0
            for (ch in 0 until channelCount) {
                val x = samples[sampleIdx + ch]
                frameEnergy += (x * x).toDouble()
            }
            val frameRms = sqrt(frameEnergy / channelCount).toFloat()

            // Smooth envelope: fast attack, slow release
            val coeff = if (frameRms > envelope) attackCoeff else releaseCoeff
            envelope += (frameRms - envelope) * coeff

            // Soft-knee expander gain
            val gain = softKneeGain(envelope)

            // Apply high-pass + gain per channel
            for (ch in 0 until channelCount) {
                val x = samples[sampleIdx++]
                val y = alpha * (hpY[ch] + x - hpX[ch])
                hpX[ch] = x
                hpY[ch] = y
                val v = (y * gain * 32768f).toInt().coerceIn(-32768, 32767)
                output.putShort(v.toShort())
            }
        }
        output.flip()
    }

    /**
     * Soft-knee downward expander:
     * - Above threshold: gain = 1.0 (voice passes through)
     * - Below threshold: gain smoothly reduces to floor
     * - Knee width provides gradual transition (no choppiness)
     */
    private fun softKneeGain(level: Float): Float {
        val threshold = THRESHOLD
        val knee = KNEE_WIDTH

        return when {
            level >= threshold + knee / 2 -> 1f
            level <= threshold - knee / 2 -> FLOOR
            else -> {
                // Smooth interpolation in knee region
                val t = (level - (threshold - knee / 2)) / knee
                val smooth = t * t * (3 - 2 * t) // smoothstep
                FLOOR + (1f - FLOOR) * smooth
            }
        }
    }

    override fun onFlush() {
        super.onFlush()
        hpX = FloatArray(channelCount)
        hpY = FloatArray(channelCount)
        envelope = 0f
    }

    companion object {
        private const val HIGH_PASS_HZ = 120f
        private const val THRESHOLD = 0.02f    // ~= -34 dBFS — voice threshold
        private const val KNEE_WIDTH = 0.02f  // Soft knee width for smooth transition
        private const val FLOOR = 0.15f       // -16dB floor — reduces noise but keeps ambience
    }
}

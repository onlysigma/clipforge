package com.clipforge.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ClipForge noise reduction — RNNoise AI edition.
 *
 * Uses the real RNNoise (Xiph.Org/Mozilla) recurrent neural network for
 * AI-powered noise suppression. RNNoise is:
 * - Trained on speech vs noise (not just a gate/filter)
 * - Real-time, low CPU (runs on phones, Raspberry Pi)
 * - Preserves voice naturally (gains 0-1, never invents audio)
 *
 * Falls back to a simple high-pass + gentle expander if native lib unavailable.
 *
 * Only 16-bit PCM is handled.
 */
class NoiseReductionAudioProcessor : BaseAudioProcessor() {

    private var channelCount: Int = 0
    private var sampleRate: Int = 44100

    private var denoiser: RnnoiseDenoiser? = null
    private var useRnnoise = false

    // Fallback: simple high-pass filter state
    private var hpX = FloatArray(0)
    private var hpY = FloatArray(0)

    // Fallback: gentle expander state
    private var envelope = FloatArray(0)

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate

        // Try RNNoise first (the real AI)
        denoiser?.release()
        denoiser = null
        useRnnoise = false

        try {
            val d = RnnoiseDenoiser(sampleRate, channelCount)
            if (d.isAvailable()) {
                denoiser = d
                useRnnoise = true
            } else {
                d.release()
            }
        } catch (_: Exception) {
            // Fall through to simple fallback
        }

        // Fallback state
        hpX = FloatArray(channelCount)
        hpY = FloatArray(channelCount)
        envelope = FloatArray(channelCount)

        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        inputBuffer.order(ByteOrder.nativeOrder())

        val output = replaceOutputBuffer(bytes)
        output.order(ByteOrder.nativeOrder())

        if (useRnnoise && denoiser != null) {
            // RNNoise path: process via AI denoiser
            val samples = ShortArray(bytes / 2)
            for (i in samples.indices) {
                samples[i] = inputBuffer.short
            }
            val denoised = denoiser!!.process(samples)
            for (s in denoised) {
                output.putShort(s)
            }
        } else {
            // Fallback: high-pass + gentle downward expander (voice-safe)
            val rc = 1f / (2f * Math.PI.toFloat() * 120f)
            val alpha = rc / (rc + 1f / sampleRate)

            val attack = 0.01f   // 10ms
            val release = 0.001f // 1000ms (very gentle)

            val totalSamples = bytes / 2
            for (i in 0 until totalSamples) {
                val ch = i % channelCount
                val x = inputBuffer.short / 32768f

                // High-pass (remove rumble)
                val y = alpha * (hpY[ch] + x - hpX[ch])
                hpX[ch] = x
                hpY[ch] = y

                // Gentle envelope follower
                val abs = kotlin.math.abs(y)
                envelope[ch] = if (abs > envelope[ch]) {
                    envelope[ch] + attack * (abs - envelope[ch])
                } else {
                    envelope[ch] + release * (abs - envelope[ch])
                }

                // Very gentle expansion: only reduce when REALLY quiet
                // Threshold -50dB, ratio 1.5:1 (barely noticeable on voice)
                val threshold = 0.00316f // -50dB
                val gain = if (envelope[ch] < threshold) {
                    0.7f + 0.3f * (envelope[ch] / threshold)
                } else {
                    1.0f
                }

                val out = (y * gain).coerceIn(-1f, 1f)
                output.putShort((out * 32767f).toInt().toShort())
            }
        }
        output.flip()
    }

    override fun onFlush() {
        super.onFlush()
        denoiser?.release()
        denoiser = null
        useRnnoise = false
        hpX = FloatArray(channelCount)
        hpY = FloatArray(channelCount)
        envelope = FloatArray(channelCount)
    }
}

package com.clipforge.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * ClipForge noise reduction — FINAL two-stage edition (v6).
 *
 * Replicates the old FFmpeg pipeline: highpass 120Hz → arnndn (RNNoise) → afftdn
 *
 * Stage 1: RNNoise AI (via NDK) — removes background noise intelligently
 * Stage 2: FFT spectral denoiser (afftdn-style, 12dB) — removes residual stationary noise
 *
 * The v5 only had Stage 1, which is why only ~20% noise was removed.
 * This adds the missing Stage 2 for the full effect.
 */
class NoiseReductionAudioProcessor : BaseAudioProcessor() {

    private var channelCount: Int = 0
    private var sampleRate: Int = 44100

    private var denoiser: RnnoiseDenoiser? = null
    private var useRnnoise = false

    // Stage 2: FFT denoiser state (per channel)
    private var noiseProfile = Array(0) { FloatArray(0) }
    private var fftOverlap = Array(0) { FloatArray(0) }
    private var fftInputBuf = Array(0) { FloatArray(0) }
    private var fftInputPos = IntArray(0)
    private var profiledFrames = 0

    // High-pass filter state
    private var hpX = FloatArray(0)
    private var hpY = FloatArray(0)

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate

        // Stage 1: RNNoise
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
        } catch (_: Exception) { }

        // Stage 2: FFT denoiser state
        val bins = FFT_SIZE / 2 + 1
        noiseProfile = Array(channelCount) { FloatArray(bins) { 1e-10f } }
        fftOverlap = Array(channelCount) { FloatArray(FFT_SIZE / 2) }
        fftInputBuf = Array(channelCount) { FloatArray(FFT_SIZE * 2) }
        fftInputPos = IntArray(channelCount)
        profiledFrames = 0

        hpX = FloatArray(channelCount)
        hpY = FloatArray(channelCount)

        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        inputBuffer.order(ByteOrder.nativeOrder())

        // Stage 0: High-pass filter (120Hz rumble removal)
        val samples = ShortArray(bytes / 2)
        val rc = 1f / (2f * PI.toFloat() * 120f)
        val alpha = rc / (rc + 1f / sampleRate)
        for (i in samples.indices) {
            val ch = i % channelCount
            val x = inputBuffer.short / 32768f
            val y = alpha * (hpY[ch] + x - hpX[ch])
            hpX[ch] = x
            hpY[ch] = y
            samples[i] = (y.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }

        // Stage 1: RNNoise AI denoising
        val stage1Out = if (useRnnoise && denoiser != null) {
            denoiser!!.process(samples)
        } else {
            samples
        }

        // Stage 2: FFT spectral denoising (afftdn-style)
        val stage2Out = fftDenoise(stage1Out)

        val output = replaceOutputBuffer(bytes)
        output.order(ByteOrder.nativeOrder())
        for (s in stage2Out) {
            output.putShort(s)
        }
        output.flip()
    }

    /**
     * Stage 2: FFT-based spectral denoiser (like FFmpeg afftdn, nr=12dB).
     * Correct overlap-add implementation (no scaling bug).
     */
    private fun fftDenoise(samples: ShortArray): ShortArray {
        val totalSamples = samples.size
        val frames = totalSamples / channelCount
        val result = ShortArray(totalSamples)

        // De-interleave into per-channel float buffers
        for (f in 0 until frames) {
            for (ch in 0 until channelCount) {
                val idx = f * channelCount + ch
                fftInputBuf[ch][fftInputPos[ch]++] = samples[idx] / 32768f
            }
        }

        // Process each channel
        val outFloat = Array(channelCount) { mutableListOf<Float>() }
        for (ch in 0 until channelCount) {
            while (fftInputPos[ch] >= FFT_SIZE) {
                val frameOut = processFftFrame(ch)
                outFloat[ch].addAll(frameOut.toList())
                // Shift by hop
                val buf = fftInputBuf[ch]
                System.arraycopy(buf, HOP_SIZE, buf, 0, fftInputPos[ch] - HOP_SIZE)
                fftInputPos[ch] -= HOP_SIZE
            }
        }

        // Re-interleave (take minimum available across channels)
        val minFrames = outFloat.minOfOrNull { it.size } ?: 0
        for (f in 0 until minFrames) {
            for (ch in 0 until channelCount) {
                val idx = f * channelCount + ch
                if (idx < totalSamples) {
                    result[idx] = (outFloat[ch][f].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
            }
        }
        // Copy any remainder as-is
        for (i in minFrames * channelCount until totalSamples) {
            result[i] = samples[i]
        }

        return result
    }

    private fun processFftFrame(ch: Int): FloatArray {
        val buf = fftInputBuf[ch]
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)

        // Hann window (analysis)
        for (i in 0 until FFT_SIZE) {
            val w = 0.5f * (1 - cos(2 * PI * i / FFT_SIZE)).toFloat()
            real[i] = buf[i] * w
        }

        fft(real, imag, false)

        val bins = FFT_SIZE / 2 + 1

        // Update noise profile from quiet frames (first 50 frames)
        var energy = 0f
        for (i in 0 until bins) {
            energy += real[i] * real[i] + imag[i] * imag[i]
        }
        val rms = sqrt(energy / bins)

        if (rms < 0.01f && profiledFrames < 50) {
            for (i in 0 until bins) {
                val p = real[i] * real[i] + imag[i] * imag[i]
                noiseProfile[ch][i] = 0.9f * noiseProfile[ch][i] + 0.1f * p
            }
            profiledFrames++
        }

        // Spectral gating: attenuate bins close to noise floor (12dB reduction)
        val reduction = 12f // dB, like afftdn default
        val linearReduction = 10f.pow(-reduction / 20f) // ~0.25

        for (i in 0 until bins) {
            val power = real[i] * real[i] + imag[i] * imag[i]
            val noisePower = noiseProfile[ch][i]
            val snr = if (noisePower > 1e-12f) power / noisePower else 100f

            // If SNR < 3dB (power < 2x noise), apply reduction
            // Otherwise keep as-is (voice preservation)
            val gain = if (snr < 2f) {
                linearReduction
            } else if (snr < 8f) {
                // Smooth transition
                val t = (snr - 2f) / 6f
                linearReduction + t * (1f - linearReduction)
            } else {
                1f
            }

            real[i] *= gain
            imag[i] *= gain

            if (i > 0 && i < FFT_SIZE / 2) {
                real[FFT_SIZE - i] *= gain
                imag[FFT_SIZE - i] *= gain
            }
        }

        fft(real, imag, true)

        // Overlap-add (CORRECT: no extra scaling, Hann COLA = 1.0 at 50% overlap)
        val overlap = fftOverlap[ch]
        val out = FloatArray(HOP_SIZE)
        for (i in 0 until HOP_SIZE) {
            // No synthesis window needed - Hann 50% overlap sums to 1.0
            out[i] = overlap[i] + real[i]
            overlap[i] = real[i + HOP_SIZE]
        }

        return out
    }

    private fun fft(real: FloatArray, imag: FloatArray, inverse: Boolean) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val angle = 2 * PI / len * (if (inverse) 1 else -1)
            val wReal = cos(angle).toFloat()
            val wImag = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var wr = 1f
                var wi = 0f
                for (k in 0 until len / 2) {
                    val ur = real[i + k]
                    val ui = imag[i + k]
                    val vr = real[i + k + len / 2] * wr - imag[i + k + len / 2] * wi
                    val vi = real[i + k + len / 2] * wi + imag[i + k + len / 2] * wr
                    real[i + k] = ur + vr
                    imag[i + k] = ui + vi
                    real[i + k + len / 2] = ur - vr
                    imag[i + k + len / 2] = ui - vi
                    val nwr = wr * wReal - wi * wImag
                    wi = wr * wImag + wi * wReal
                    wr = nwr
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            for (i in 0 until n) {
                real[i] = real[i] / n
                imag[i] = imag[i] / n
            }
        }
    }

    override fun onFlush() {
        super.onFlush()
        denoiser?.release()
        denoiser = null
        useRnnoise = false
        val bins = FFT_SIZE / 2 + 1
        noiseProfile = Array(channelCount) { FloatArray(bins) { 1e-10f } }
        fftOverlap = Array(channelCount) { FloatArray(FFT_SIZE / 2) }
        fftInputBuf = Array(channelCount) { FloatArray(FFT_SIZE * 2) }
        fftInputPos = IntArray(channelCount)
        profiledFrames = 0
        hpX = FloatArray(channelCount)
        hpY = FloatArray(channelCount)
    }

    companion object {
        private const val FFT_SIZE = 2048
        private const val HOP_SIZE = 1024
    }
}

package com.clipforge.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * v4 spectral noise reduction — voice-preserving edition.
 *
 * Fixes v3's "voice removed" bug:
 * - Wiener filtering instead of aggressive spectral subtraction (statistically optimal,
 *   preserves speech much better)
 * - Frequency-dependent processing: gentle in speech bands (300Hz-3.4kHz), stronger elsewhere
 * - Conservative noise estimation (only very quiet frames update the noise profile)
 * - Higher spectral floor (0.25) to prevent over-suppression
 *
 * Only 16-bit PCM is handled.
 */
class NoiseReductionAudioProcessor : BaseAudioProcessor() {

    private var channelCount: Int = 0
    private var sampleRate: Int = 44100

    private var noisePower = Array(0) { FloatArray(0) }
    private var overlapBuffer = Array(0) { FloatArray(0) }
    private var inputBuffer = Array(0) { FloatArray(0) }
    private var inputPos = IntArray(0)

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

        val bins = FFT_SIZE / 2 + 1
        noisePower = Array(channelCount) { FloatArray(bins) { 1e-8f } }
        overlapBuffer = Array(channelCount) { FloatArray(FFT_SIZE / 2) }
        inputBuffer = Array(channelCount) { FloatArray(FFT_SIZE * 2) }
        inputPos = IntArray(channelCount)
        hpX = FloatArray(channelCount)
        hpY = FloatArray(channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        inputBuffer.order(ByteOrder.nativeOrder())

        val totalSamples = bytes / 2
        val frames = totalSamples / channelCount
        val output = replaceOutputBuffer(bytes + HOP_SIZE * 2 * channelCount)
        output.order(ByteOrder.nativeOrder())

        val rc = 1f / (2f * PI.toFloat() * HIGH_PASS_HZ)
        val alpha = rc / (rc + 1f / sampleRate)

        for (f in 0 until frames) {
            for (ch in 0 until channelCount) {
                val x = inputBuffer.short / 32768f
                val y = alpha * (hpY[ch] + x - hpX[ch])
                hpX[ch] = x
                hpY[ch] = y
                this.inputBuffer[ch][inputPos[ch]++] = y
            }
        }

        for (ch in 0 until channelCount) {
            while (inputPos[ch] >= FFT_SIZE) {
                processFrame(ch, output)
                val buf = this.inputBuffer[ch]
                System.arraycopy(buf, HOP_SIZE, buf, 0, inputPos[ch] - HOP_SIZE)
                inputPos[ch] -= HOP_SIZE
            }
        }
        output.flip()
    }

    private fun processFrame(ch: Int, output: ByteBuffer) {
        val buf = inputBuffer[ch]
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)

        for (i in 0 until FFT_SIZE) {
            val w = 0.5f * (1 - cos(2 * PI * i / FFT_SIZE)).toFloat()
            real[i] = buf[i] * w
        }

        fft(real, imag, false)

        val bins = FFT_SIZE / 2 + 1
        val binHz = sampleRate.toFloat() / FFT_SIZE

        // Compute power spectrum
        val power = FloatArray(bins)
        for (i in 0 until bins) {
            power[i] = real[i] * real[i] + imag[i] * imag[i]
        }

        // Frame energy for VAD (voice activity detection)
        var energy = 0f
        for (p in power) energy += p
        val rms = sqrt(energy / bins)

        // Update noise estimate ONLY on very quiet frames (conservative VAD)
        if (rms < NOISE_THRESHOLD) {
            for (i in 0 until bins) {
                noisePower[ch][i] = (1 - ADAPT_RATE) * noisePower[ch][i] + ADAPT_RATE * power[i]
            }
        }

        // Wiener filtering with frequency-dependent strength
        for (i in 0 until bins) {
            val freq = i * binHz
            val noisyPower = power[i]
            val noiseP = noisePower[ch][i]

            // Wiener gain: P_clean / (P_clean + P_noise)
            // P_clean estimated as max(noisy - noise, 0)
            val cleanEst = max(noisyPower - noiseP, 0f)
            var gain = if (noisyPower > 1e-10f) {
                cleanEst / (cleanEst + noiseP)
            } else 0f

            // Frequency-dependent: be GENTLE in speech band (300-3400 Hz)
            val speechBandFactor = when {
                freq in 300f..3400f -> 0.5f // Only 50% of Wiener effect in speech band
                freq < 150f -> 1.0f // Full effect on rumble
                else -> 0.8f // Moderate elsewhere
            }
            // Blend: gain = 1 - factor * (1 - wienerGain)
            // factor=0 -> gain=1 (no change), factor=1 -> full Wiener
            gain = 1f - speechBandFactor * (1f - gain)

            // Apply spectral floor to prevent over-suppression
            if (gain < SPECTRAL_FLOOR) gain = SPECTRAL_FLOOR

            val mag = sqrt(noisyPower)
            val newMag = mag * gain
            val phase = atan2(imag[i], real[i])
            real[i] = newMag * cos(phase)
            imag[i] = newMag * sin(phase)
            if (i > 0 && i < FFT_SIZE / 2) {
                real[FFT_SIZE - i] = real[i]
                imag[FFT_SIZE - i] = -imag[i]
            }
        }

        fft(real, imag, true)

        // Overlap-add
        val overlap = overlapBuffer[ch]
        for (i in 0 until HOP_SIZE) {
            val w = 0.5f * (1 - cos(2 * PI * i / FFT_SIZE)).toFloat()
            val sample = (overlap[i] + real[i] * w / (FFT_SIZE / 4)).coerceIn(-1f, 1f)
            output.putShort((sample * 32767f).toInt().toShort())
        }
        for (i in 0 until HOP_SIZE) {
            val w = 0.5f * (1 - cos(2 * PI * (i + HOP_SIZE) / FFT_SIZE)).toFloat()
            overlap[i] = real[i + HOP_SIZE] * w / (FFT_SIZE / 4)
        }
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
        val bins = FFT_SIZE / 2 + 1
        noisePower = Array(channelCount) { FloatArray(bins) { 1e-8f } }
        overlapBuffer = Array(channelCount) { FloatArray(FFT_SIZE / 2) }
        inputBuffer = Array(channelCount) { FloatArray(FFT_SIZE * 2) }
        inputPos = IntArray(channelCount)
        hpX = FloatArray(channelCount)
        hpY = FloatArray(channelCount)
    }

    companion object {
        private const val FFT_SIZE = 2048
        private const val HOP_SIZE = 1024
        private const val HIGH_PASS_HZ = 120f
        private const val NOISE_THRESHOLD = 0.008f // Very conservative: only true silence
        private const val ADAPT_RATE = 0.05f // Slow adaptation
        private const val SPECTRAL_FLOOR = 0.25f // Never go below 25% — preserves voice
    }
}

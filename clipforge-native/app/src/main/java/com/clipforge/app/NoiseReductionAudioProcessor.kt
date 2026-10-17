package com.clipforge.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * v3 spectral noise reduction for ClipForge Native.
 *
 * FFT-based spectral subtraction (RNNoise-style approach, pure Kotlin):
 * 1. Overlapping Hann windows (2048 samples, 50% overlap)
 * 2. Noise spectrum estimated from quiet frames (adaptive)
 * 3. Spectral subtraction with over-subtraction + spectral floor
 * 4. Inverse FFT + overlap-add reconstruction
 *
 * This actually REMOVES noise from speech (not just gating silence),
 * like the old FFmpeg arnndn chain did.
 *
 * Only 16-bit PCM mono/stereo is handled.
 */
class NoiseReductionAudioProcessor : BaseAudioProcessor() {

    private var channelCount: Int = 0
    private var sampleRate: Int = 44100

    // Per-channel processing state
    private var noiseSpectrum = Array(0) { FloatArray(0) }
    private var overlapBuffer = Array(0) { FloatArray(0) }
    private var inputBuffer = Array(0) { FloatArray(0) }
    private var inputPos = IntArray(0)
    private var noiseFrames = 0

    // High-pass filter state (120 Hz rumble removal)
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
        noiseSpectrum = Array(channelCount) { FloatArray(bins) { 1e-6f } }
        overlapBuffer = Array(channelCount) { FloatArray(FFT_SIZE / 2) }
        inputBuffer = Array(channelCount) { FloatArray(FFT_SIZE * 2) }
        inputPos = IntArray(channelCount)
        noiseFrames = 0
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

        // Output: same number of samples (overlap-add preserves length after priming)
        val output = replaceOutputBuffer(bytes + HOP_SIZE * 2 * channelCount)
        output.order(ByteOrder.nativeOrder())

        // High-pass coefficients
        val rc = 1f / (2f * PI.toFloat() * HIGH_PASS_HZ)
        val alpha = rc / (rc + 1f / sampleRate)

        // De-interleave and high-pass into per-channel buffers
        for (f in 0 until frames) {
            for (ch in 0 until channelCount) {
                val x = inputBuffer.short / 32768f
                val y = alpha * (hpY[ch] + x - hpX[ch])
                hpX[ch] = x
                hpY[ch] = y
                this.inputBuffer[ch][inputPos[ch]++] = y
            }
        }

        // Process complete frames
        for (ch in 0 until channelCount) {
            while (inputPos[ch] >= FFT_SIZE) {
                processFrame(ch, output)
                // Shift by hop size
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

        // Apply Hann window
        for (i in 0 until FFT_SIZE) {
            val w = 0.5f * (1 - cos(2 * PI * i / FFT_SIZE)).toFloat()
            real[i] = buf[i] * w
        }

        // Forward FFT
        fft(real, imag, false)

        val bins = FFT_SIZE / 2 + 1
        val magnitudes = FloatArray(bins)
        val phases = FloatArray(bins)
        for (i in 0 until bins) {
            magnitudes[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
            phases[i] = atan2(imag[i], real[i])
        }

        // Estimate frame energy for noise detection
        var energy = 0f
        for (m in magnitudes) energy += m * m
        val rms = sqrt(energy / bins)

        // Update noise spectrum during quiet frames (adaptive)
        if (rms < NOISE_THRESHOLD && noiseFrames < MAX_NOISE_FRAMES) {
            val adapt = 0.1f // adaptation rate
            for (i in 0 until bins) {
                noiseSpectrum[ch][i] = (1 - adapt) * noiseSpectrum[ch][i] + adapt * magnitudes[i]
            }
            noiseFrames++
        }

        // Spectral subtraction with over-subtraction and flooring
        for (i in 0 until bins) {
            val noise = noiseSpectrum[ch][i]
            var clean = magnitudes[i] - OVER_SUBTRACTION * noise
            // Spectral floor: don't go below 10% of original or noise floor
            val floor = max(magnitudes[i] * SPECTRAL_FLOOR, noise * 0.1f)
            if (clean < floor) clean = floor

            real[i] = clean * cos(phases[i])
            imag[i] = clean * sin(phases[i])
            // Mirror for real FFT
            if (i > 0 && i < FFT_SIZE / 2) {
                real[FFT_SIZE - i] = real[i]
                imag[FFT_SIZE - i] = -imag[i]
            }
        }

        // Inverse FFT
        fft(real, imag, true)

        // Overlap-add with Hann window (COLA compliant for 50% overlap)
        val overlap = overlapBuffer[ch]
        for (i in 0 until HOP_SIZE) {
            val w = 0.5f * (1 - cos(2 * PI * i / FFT_SIZE)).toFloat()
            val sample = (overlap[i] + real[i] * w / (FFT_SIZE / 2))
                .coerceIn(-1f, 1f)
            output.putShort((sample * 32767f).toInt().toShort())
        }
        // Save second half for overlap
        for (i in 0 until HOP_SIZE) {
            val w = 0.5f * (1 - cos(2 * PI * (i + HOP_SIZE) / FFT_SIZE)).toFloat()
            overlap[i] = real[i + HOP_SIZE] * w / (FFT_SIZE / 2)
        }
    }

    /**
     * In-place radix-2 FFT.
     */
    private fun fft(real: FloatArray, imag: FloatArray, inverse: Boolean) {
        val n = real.size

        // Bit-reversal permutation
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

        // Cooley-Tukey
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
                real[i] /= n
                imag[i] /= n
            }
        }
    }

    override fun onFlush() {
        super.onFlush()
        val bins = FFT_SIZE / 2 + 1
        noiseSpectrum = Array(channelCount) { FloatArray(bins) { 1e-6f } }
        overlapBuffer = Array(channelCount) { FloatArray(FFT_SIZE / 2) }
        inputBuffer = Array(channelCount) { FloatArray(FFT_SIZE * 2) }
        inputPos = IntArray(channelCount)
        noiseFrames = 0
        hpX = FloatArray(channelCount)
        hpY = FloatArray(channelCount)
    }

    companion object {
        private const val FFT_SIZE = 2048
        private const val HOP_SIZE = 1024 // 50% overlap
        private const val HIGH_PASS_HZ = 120f
        private const val NOISE_THRESHOLD = 0.015f // RMS below this = noise frame
        private const val MAX_NOISE_FRAMES = 100 // Adapt for first ~2 seconds
        private const val OVER_SUBTRACTION = 2.0f // Aggressive noise removal
        private const val SPECTRAL_FLOOR = 0.1f // Keep 10% to avoid artifacts
    }
}

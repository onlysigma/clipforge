package com.clipforge.app

/**
 * JNI bridge to RNNoise — the real AI noise suppression by Xiph.Org/Mozilla.
 *
 * RNNoise uses a recurrent neural network trained on speech vs noise.
 * It processes 48kHz mono audio in 480-sample (10ms) frames and returns
 * per-band gains (0-1) that preserve voice while removing background noise.
 *
 * If the native library fails to load, falls back gracefully.
 */
object RnnoiseNative {

    private var loaded = false
    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("rnnoise_jni")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message
            loaded = false
        }
    }

    fun isAvailable(): Boolean = loaded
    fun getLoadError(): String? = loadError

    // JNI methods (implemented in rnnoise_jni.c)
    external fun create(): Long
    external fun destroy(handle: Long)
    external fun getFrameSize(): Int
    external fun processFrame(handle: Long, input: FloatArray, output: FloatArray): Float
}

/**
 * High-level RNNoise denoiser with resampling support.
 *
 * RNNoise requires 48kHz mono. This class handles:
 * - Stereo to mono conversion (if needed)
 * - Resampling to/from 48kHz (if needed)
 * - Frame-based processing with overlap handling
 */
class RnnoiseDenoiser(
    private val sampleRate: Int,
    private val channels: Int
) {
    private var handle: Long = 0
    private val frameSize: Int
    private val available: Boolean

    // Resampling state (simple linear interpolation)
    private var resamplePos = 0.0

    init {
        available = RnnoiseNative.isAvailable()
        if (available) {
            handle = RnnoiseNative.create()
            frameSize = RnnoiseNative.getFrameSize() // 480
        } else {
            frameSize = 480
        }
    }

    fun isAvailable(): Boolean = available && handle != 0L

    /**
     * Process interleaved 16-bit PCM samples.
     * Returns denoised samples (same format).
     */
    fun process(samples: ShortArray): ShortArray {
        if (!isAvailable()) return samples

        // Convert to mono float at 48kHz
        val mono48k = resampleTo48kMono(samples)

        // Process in RNNoise frames
        val output48k = FloatArray(mono48k.size)
        val inFrame = FloatArray(frameSize)
        val outFrame = FloatArray(frameSize)

        var pos = 0
        while (pos + frameSize <= mono48k.size) {
            mono48k.copyInto(inFrame, 0, pos, pos + frameSize)
            RnnoiseNative.processFrame(handle, inFrame, outFrame)
            outFrame.copyInto(output48k, pos)
            pos += frameSize
        }
        // Copy remainder as-is
        if (pos < mono48k.size) {
            mono48k.copyInto(output48k, pos, pos, mono48k.size)
        }

        // Convert back to original format
        return resampleFrom48kMono(output48k, samples.size)
    }

    private fun resampleTo48kMono(samples: ShortArray): FloatArray {
        val frames = samples.size / channels
        val ratio = 48000.0 / sampleRate
        val outFrames = (frames * ratio).toInt()
        val result = FloatArray(outFrames)

        for (i in 0 until outFrames) {
            val srcPos = i / ratio
            val srcIdx = srcPos.toInt()
            val frac = (srcPos - srcIdx).toFloat()

            if (srcIdx + 1 < frames) {
                // Average channels for mono, linear interpolate for resampling
                var s0 = 0f
                var s1 = 0f
                for (ch in 0 until channels) {
                    s0 += samples[srcIdx * channels + ch] / 32768f
                    s1 += samples[(srcIdx + 1) * channels + ch] / 32768f
                }
                s0 /= channels
                s1 /= channels
                result[i] = s0 + (s1 - s0) * frac
            } else if (srcIdx < frames) {
                var s = 0f
                for (ch in 0 until channels) {
                    s += samples[srcIdx * channels + ch] / 32768f
                }
                result[i] = s / channels
            }
        }
        return result
    }

    private fun resampleFrom48kMono(mono48k: FloatArray, originalSize: Int): ShortArray {
        val frames = originalSize / channels
        val ratio = sampleRate / 48000.0
        val result = ShortArray(originalSize)

        for (i in 0 until frames) {
            val srcPos = i / ratio
            val srcIdx = srcPos.toInt().coerceIn(0, mono48k.size - 1)
            val sample = mono48k[srcIdx].coerceIn(-1f, 1f)
            val shortVal = (sample * 32767f).toInt().toShort()
            for (ch in 0 until channels) {
                result[i * channels + ch] = shortVal
            }
        }
        return result
    }

    fun release() {
        if (handle != 0L) {
            RnnoiseNative.destroy(handle)
            handle = 0
        }
    }
}

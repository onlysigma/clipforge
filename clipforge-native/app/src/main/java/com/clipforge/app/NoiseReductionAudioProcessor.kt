package com.clipforge.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * v1 noise reduction for ClipForge Native.
 *
 * A full RNNoise NDK integration is out of scope for v1, so this applies two
 * lightweight, genuinely useful stages on the decoded PCM before AAC encoding:
 *   1. One-pole high-pass filter (~120 Hz) — removes rumble, hum and handling noise.
 *   2. RMS noise gate — strongly attenuates sections quieter than ~-38 dBFS
 *      (room tone / fan hiss between speech), with fast close / slow open
 *      smoothing to avoid chattering.
 *
 * Only 16-bit PCM is handled; Transformer always decodes to PCM 16-bit here.
 */
class NoiseReductionAudioProcessor : BaseAudioProcessor() {

    private var channelCount: Int = 0
    private var sampleRate: Int = 44100
    private var hpX = FloatArray(0) // previous input per channel
    private var hpY = FloatArray(0) // previous output per channel
    private var gateGain = 1f

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
        gateGain = 1f
        return inputAudioFormat // in-place format, output == input
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(bytes)
        output.order(ByteOrder.nativeOrder())

        val totalSamples = bytes / 2
        val filtered = FloatArray(totalSamples)

        // One-pole high-pass coefficient for HIGH_PASS_HZ.
        val rc = 1f / (2f * PI.toFloat() * HIGH_PASS_HZ)
        val alpha = rc / (rc + 1f / sampleRate)

        var energy = 0.0
        var i = 0
        val frames = totalSamples / channelCount
        repeat(frames) {
            for (ch in 0 until channelCount) {
                val x = inputBuffer.short / 32768f
                val y = alpha * (hpY[ch] + x - hpX[ch])
                hpX[ch] = x
                hpY[ch] = y
                filtered[i++] = y
                energy += (y * y).toDouble()
            }
        }

        // Noise gate on the buffer's RMS.
        val rms = sqrt(energy / totalSamples).toFloat()
        val target = if (rms < GATE_THRESHOLD) GATE_FLOOR else 1f
        val coeff = if (target < gateGain) 0.7f else 0.1f // fast close, slow open
        gateGain += (target - gateGain) * coeff

        for (s in filtered) {
            val v = (s * gateGain * 32768f).toInt().coerceIn(-32768, 32767)
            output.putShort(v.toShort())
        }
        output.flip() // expose written bytes between position and limit
    }

    override fun onFlush() {
        // Deprecated no-arg version — delegate to the new API for safety.
        onFlush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        super.onFlush(streamMetadata)
        hpX = FloatArray(channelCount)
        hpY = FloatArray(channelCount)
        gateGain = 1f
    }

    companion object {
        private const val HIGH_PASS_HZ = 120f
        private const val GATE_THRESHOLD = 0.012f // ~= -38 dBFS
        private const val GATE_FLOOR = 0.06f     // residual level when gated
    }
}

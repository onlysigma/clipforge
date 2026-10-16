package com.clipforge.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.Effect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Concatenates clips into one MP4 using AndroidX Media3 Transformer.
 *
 * This is Google's official video editing stack: decoding, GPU effects and
 * encoding all run on hardware (MediaCodec), which is why it is dramatically
 * faster than the old FFmpeg software path — the same reason CapCut is fast.
 *
 * Per clip, applied in order:
 *   1. [Presentation] — scale/pad (letterbox) every clip to the exact output
 *      size so concatenation is seamless.
 *   2. [PremiumColorMatrix] — GPU saturation/contrast grade (optional).
 *   3. [NoiseReductionAudioProcessor] — high-pass + noise gate (optional).
 *
 * Output: H.264 video + AAC audio in MP4. All Transformer calls happen on the
 * application (main) thread, as required by the API.
 */
class VideoProcessor(private val context: Context) {

    data class Options(
        val noiseReduction: Boolean,
        val premiumFilter: Boolean,
        val outWidth: Int,
        val outHeight: Int
    )

    interface Listener {
        fun onProgress(percent: Int)
        fun onComplete(output: File)
        fun onError(e: Exception)
    }

    private var transformer: Transformer? = null
    private var progressHandler: Handler? = null
    private var progressRunnable: Runnable? = null

    fun isRunning(): Boolean = transformer != null

    fun start(
        clips: List<Uri>,
        output: File,
        options: Options,
        listener: Listener
    ) {
        cancel() // just in case
        require(clips.isNotEmpty()) { "No clips to build" }

        val items = clips.map { uri ->
            // Fresh effect instances per clip — effects may hold per-item state.
            val videoEffects = mutableListOf<Effect>()
            videoEffects += Presentation.createForWidthAndHeight(
                options.outWidth,
                options.outHeight,
                Presentation.LAYOUT_SCALE_TO_FIT // scale + pad (letterbox), never crop
            )
            if (options.premiumFilter) {
                videoEffects += PremiumColorMatrix()
            }
            // NOTE: Effects takes audio processors FIRST, video effects second.
            val audioProcessors: List<AudioProcessor> =
                if (options.noiseReduction) listOf(NoiseReductionAudioProcessor())
                else emptyList()
            EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(audioProcessors, videoEffects))
                .build()
        }

        @Suppress("DEPRECATION")
        val sequence = EditedMediaItemSequence.Builder(items).build()
        val composition = Composition.Builder(listOf(sequence)).build()

        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult
                ) {
                    stopProgressPolling()
                    transformer = null
                    listener.onComplete(output)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    stopProgressPolling()
                    transformer = null
                    // Surface the REAL error, not a generic message.
                    listener.onError(exportException)
                }
            })
            .build()

        transformer = t
        if (output.exists()) output.delete()
        output.parentFile?.mkdirs()

        t.start(composition, output.absolutePath)
        startProgressPolling(t, listener)
    }

    fun cancel() {
        stopProgressPolling()
        try {
            transformer?.cancel()
        } catch (_: Exception) {
            // already finished / never started
        }
        transformer = null
    }

    private fun startProgressPolling(t: Transformer, listener: Listener) {
        val handler = Handler(Looper.getMainLooper())
        progressHandler = handler
        val holder = ProgressHolder()
        var last = -1
        val r = object : Runnable {
            override fun run() {
                val state = t.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE && holder.progress != last) {
                    last = holder.progress
                    listener.onProgress(last)
                }
                handler.postDelayed(this, 400)
            }
        }
        progressRunnable = r
        handler.post(r)
    }

    private fun stopProgressPolling() {
        progressRunnable?.let { progressHandler?.removeCallbacks(it) }
        progressRunnable = null
        progressHandler = null
    }
}

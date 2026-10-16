package com.clipforge.app

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections

enum class AspectRatio(val label: String) {
    LANDSCAPE("16:9"),
    PORTRAIT("9:16"),
    SQUARE("1:1")
}

enum class Resolution(val label: String, val longEdge: Int) {
    P1080("1080p", 1080),
    P720("720p", 720)
}

/** Exact output dimensions for an aspect ratio + resolution choice. */
fun outputSize(aspect: AspectRatio, res: Resolution): Pair<Int, Int> {
    val long = res.longEdge
    return when (aspect) {
        AspectRatio.LANDSCAPE -> (long * 16 / 9) to long   // 1920x1080 / 1280x720
        AspectRatio.PORTRAIT -> long to (long * 16 / 9)    // 1080x1920 / 720x1280
        AspectRatio.SQUARE -> long to long                 // 1080x1080 / 720x720
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

data class UiState(
    val clips: List<ClipItem> = emptyList(),
    val noiseReduction: Boolean = true,
    val premiumFilter: Boolean = true,
    val aspectRatio: AspectRatio = AspectRatio.LANDSCAPE,
    val resolution: Resolution = Resolution.P1080,
    val isBuilding: Boolean = false,
    val progress: Int = 0,
    val error: String? = null,
    val outputFile: File? = null
)

class ClipForgeViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val processor = VideoProcessor(app.applicationContext)

    fun addClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val items = uris.map { uri ->
                var name = "clip.mp4"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
                var dur = 0L
                try {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(getApplication(), uri)
                    dur = mmr.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                    mmr.release()
                } catch (_: Exception) {
                    // leave duration unknown
                }
                ClipItem(uri = uri, displayName = name, durationMs = dur)
            }
            _ui.update { it.copy(clips = it.clips + items, outputFile = null, error = null) }
        }
    }

    fun removeClip(id: String) {
        _ui.update { it.copy(clips = it.clips.filterNot { c -> c.id == id }) }
    }

    fun moveClip(id: String, delta: Int) {
        _ui.update { state ->
            val list = state.clips.toMutableList()
            val i = list.indexOfFirst { it.id == id }
            val j = i + delta
            if (i < 0 || j < 0 || j >= list.size) return@update state
            Collections.swap(list, i, j)
            state.copy(clips = list)
        }
    }

    fun setNoiseReduction(v: Boolean) = _ui.update { it.copy(noiseReduction = v) }
    fun setPremiumFilter(v: Boolean) = _ui.update { it.copy(premiumFilter = v) }
    fun setAspectRatio(a: AspectRatio) = _ui.update { it.copy(aspectRatio = a) }
    fun setResolution(r: Resolution) = _ui.update { it.copy(resolution = r) }
    fun clearError() = _ui.update { it.copy(error = null) }
    fun clearOutput() = _ui.update { it.copy(outputFile = null) }

    fun build() {
        val state = _ui.value
        if (state.clips.isEmpty() || state.isBuilding) return
        val (w, h) = outputSize(state.aspectRatio, state.resolution)
        val movies = getApplication<Application>()
            .getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val out = File(movies, "clipforge_${System.currentTimeMillis()}.mp4")

        _ui.update { it.copy(isBuilding = true, progress = 0, error = null, outputFile = null) }

        // Transformer must be driven from the application (main) thread —
        // viewModelScope runs on Main, so this is safe.
        try {
            processor.start(
                clips = state.clips.map { it.uri },
                output = out,
                options = VideoProcessor.Options(
                    noiseReduction = state.noiseReduction,
                    premiumFilter = state.premiumFilter,
                    outWidth = w,
                    outHeight = h
                ),
                listener = object : VideoProcessor.Listener {
                    override fun onProgress(p: Int) {
                        _ui.update { it.copy(progress = p) }
                    }

                    override fun onComplete(f: File) {
                        _ui.update {
                            it.copy(isBuilding = false, progress = 100, outputFile = f)
                        }
                    }

                    override fun onError(e: Exception) {
                        val msg = e.message?.takeIf { it.isNotBlank() }
                            ?: e.cause?.message?.takeIf { it.isNotBlank() }
                            ?: e.toString()
                        _ui.update { it.copy(isBuilding = false, error = msg) }
                    }
                }
            )
        } catch (e: Exception) {
            _ui.update { it.copy(isBuilding = false, error = e.message ?: e.toString()) }
        }
    }

    fun cancelBuild() {
        processor.cancel()
        _ui.update { it.copy(isBuilding = false) }
    }

    override fun onCleared() {
        processor.cancel()
        super.onCleared()
    }
}

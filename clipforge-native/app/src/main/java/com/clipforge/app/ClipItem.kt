package com.clipforge.app

import android.net.Uri
import java.util.UUID

/** One video clip in the build list. */
data class ClipItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val displayName: String,
    val durationMs: Long = 0L
)

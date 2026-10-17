package com.clipforge.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

private val AppColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF111111),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF3A3A3A)
)

class MainActivity : ComponentActivity() {

    private val vm: ClipForgeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                ClipForgeScreen(vm)
            }
        }
    }
}

@Composable
fun ClipForgeScreen(vm: ClipForgeViewModel) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> vm.addClips(uris) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        // ---- Scrollable content ----
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
        // ---- Header ----
        Text("ClipForge", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "Native • GPU encoding",
            fontSize = 13.sp,
            color = Color(0xFFB0B0B0)
        )
        Spacer(Modifier.height(12.dp))

        // ---- Clip list ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Clips (${ui.clips.size})",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { picker.launch("video/*") },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add clips")
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .height(260.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ui.clips, key = { it.id }) { clip ->
                ClipRow(
                    clip = clip,
                    onUp = { vm.moveClip(clip.id, -1) },
                    onDown = { vm.moveClip(clip.id, 1) },
                    onRemove = { vm.removeClip(clip.id) }
                )
            }
        }
        if (ui.clips.isEmpty()) {
            Text(
                "No clips yet — tap “Add clips” to pick videos.",
                color = Color(0xFFB0B0B0),
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(12.dp))

        // ---- Format ----
        SectionLabel("Format")
        SegmentedRow(
            options = AspectRatio.values().map { it.label },
            selected = ui.aspectRatio.label,
            onSelect = { label ->
                vm.setAspectRatio(AspectRatio.values().first { it.label == label })
            }
        )
        Spacer(Modifier.height(8.dp))
        SectionLabel("Resolution")
        SegmentedRow(
            options = Resolution.values().map { it.label },
            selected = ui.resolution.label,
            onSelect = { label ->
                vm.setResolution(Resolution.values().first { it.label == label })
            }
        )
        Spacer(Modifier.height(12.dp))

        // ---- Toggles ----
        ToggleRow(
            title = "Noise cancellation",
            subtitle = "High-pass + noise gate on voices",
            checked = ui.noiseReduction,
            onChange = vm::setNoiseReduction
        )
        ToggleRow(
            title = "Premium filter",
            subtitle = "Contrast, colour and saturation grade",
            checked = ui.premiumFilter,
            onChange = vm::setPremiumFilter
        )
        Spacer(Modifier.height(12.dp))

        // ---- Error card (real error, not generic) ----
        ui.error?.let { err ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1212)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        err,
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = vm::clearError, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- Output card ----
        ui.outputFile?.let { file ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF122A12)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        file.name,
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { downloadFile(context, file) }) {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text("Save", color = Color.White)
                    }
                    TextButton(onClick = { shareFile(context, file) }) {
                        Icon(Icons.Filled.Share, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text("Share", color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- Progress ----
        if (ui.isBuilding) {
            Text("Building… ${ui.progress}%", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = ui.progress / 100f,
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                trackColor = Color(0xFF3A3A3A)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = vm::cancelBuild,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) { Text("Cancel") }
            Spacer(Modifier.height(8.dp))
        }
        } // end scrollable content

        Spacer(Modifier.height(8.dp))

        // ---- Build button (fixed at bottom) ----
        Button(
            onClick = vm::build,
            enabled = ui.clips.isNotEmpty() && !ui.isBuilding,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
                disabledContainerColor = Color(0xFF3A3A3A),
                disabledContentColor = Color(0xFF888888)
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Build Final Video", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFFB0B0B0),
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun SegmentedRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E1E))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { opt ->
            val isSel = opt == selected
            TextButton(
                onClick = { onSelect(opt) },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSel) Color.White else Color.Transparent),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (isSel) Color.Black else Color.White
                )
            ) {
                Text(opt, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color(0xFFB0B0B0), fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF555555),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF2A2A2A)
            )
        )
    }
}

@Composable
private fun ClipRow(
    clip: ClipItem,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    // Load video thumbnail via MediaMetadataRetriever (reliable, no Coil video decoder needed)
    var thumbnail by remember(clip.uri) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    LaunchedEffect(clip.uri) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, clip.uri)
                val bmp = retriever.getFrameAtTime(
                    1_000_000,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                retriever.release()
                thumbnail = bmp
            } catch (_: Exception) {
                thumbnail = null
            }
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A2A)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF666666),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    clip.displayName,
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatDuration(clip.durationMs),
                    color = Color(0xFFB0B0B0),
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = onUp, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up", tint = Color.White)
            }
            IconButton(onClick = onDown, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down", tint = Color.White)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = Color.White)
            }
        }
    }
}

private fun shareFile(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share video"))
}

private fun downloadFile(context: android.content.Context, file: File) {
    try {
        val resolver = context.contentResolver

        // Try MediaStore first (Android 10+)
        try {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/ClipForge"
                    )
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.provider.MediaStore.Video.Media.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                )
            } else {
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val uri = resolver.insert(collection, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                android.widget.Toast.makeText(
                    context, "Saved to Downloads/ClipForge", android.widget.Toast.LENGTH_SHORT
                ).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("ClipForge", "MediaStore save failed, trying fallback", e)
        }

        // Fallback: copy to app's external Downloads directory
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val destDir = java.io.File(downloadsDir, "ClipForge")
        destDir.mkdirs()
        val dest = java.io.File(destDir, file.name)
        file.copyTo(dest, overwrite = true)

        // Notify media scanner
        android.media.MediaScannerConnection.scanFile(
            context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null
        )
        android.widget.Toast.makeText(
            context, "Saved to Downloads/ClipForge", android.widget.Toast.LENGTH_SHORT
        ).show()
    } catch (e: Exception) {
        android.util.Log.e("ClipForge", "Save failed", e)
        android.widget.Toast.makeText(
            context, "Save failed: ${e.message}", android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

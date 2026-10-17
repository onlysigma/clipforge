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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    background = Color(0xFF0C0C0C),
    onBackground = Color.White,
    surface = Color(0xFF121212),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF999999),
    outline = Color(0xFF2A2A2A)
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
            .background(Color(0xFF0C0C0C))
            .navigationBarsPadding()
    ) {
        // ---- App bar ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Movie,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "ClipForge",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = (-0.3).sp
                )
            }
            // Export button (shares last output if available)
            TextButton(
                onClick = {
                    ui.outputFile?.let { shareFile(context, it) }
                },
                enabled = ui.outputFile != null,
                colors = ButtonDefaults.textButtonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    disabledContainerColor = Color(0xFF2A2A2A),
                    disabledContentColor = Color(0xFF666666)
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(
                    Icons.Filled.IosShare,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Export", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ---- Scrollable content ----
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // ---- Preview hero ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(172.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF141414)),
                contentAlignment = Alignment.Center
            ) {
                // Show first clip thumbnail as preview if available
                val firstClip = ui.clips.firstOrNull()
                if (firstClip != null) {
                    ClipThumbnail(
                        uri = firstClip.uri,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(Color.White.copy(alpha = 0.92f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Preview",
                                tint = Color.Black,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                } else {
                    Icon(
                        Icons.Filled.Movie,
                        contentDescription = null,
                        tint = Color(0xFF333333),
                        modifier = Modifier.size(44.dp)
                    )
                }
                // Duration badge
                if (ui.clips.isNotEmpty()) {
                    val totalMs = ui.clips.sumOf { it.durationMs }
                    Text(
                        formatDuration(totalMs),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                    Text(
                        "Preview",
                        fontSize = 10.sp,
                        color = Color(0xFF999999),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            // ---- Timeline header ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "TIMELINE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF777777),
                    letterSpacing = 1.2.sp
                )
                TextButton(
                    onClick = { picker.launch("video/*") },
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = Color(0xFF1A1A1A),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text("Add", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // ---- Horizontal timeline ----
            if (ui.clips.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp
                    )
                ) {
                    items(ui.clips, key = { it.id }) { clip ->
                        TimelineThumb(
                            clip = clip,
                            index = ui.clips.indexOf(clip),
                            selected = false,
                            onRemove = { vm.removeClip(clip.id) }
                        )
                    }
                }
            } else {
                Text(
                    "No clips yet — tap Add to pick videos",
                    color = Color(0xFF666666),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(4.dp))

            // ---- Tool icons row ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF121212))
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ToolIcon(
                    icon = Icons.Filled.AspectRatio,
                    label = "Ratio",
                    active = false,
                    onClick = { /* scroll to ratio */ }
                )
                ToolIcon(
                    icon = Icons.Filled.HighQuality,
                    label = "Quality",
                    active = false,
                    onClick = { /* scroll to resolution */ }
                )
                ToolIcon(
                    icon = Icons.Filled.Hearing,
                    label = "Denoise",
                    active = ui.noiseReduction,
                    onClick = { vm.setNoiseReduction(!ui.noiseReduction) }
                )
                ToolIcon(
                    icon = Icons.Filled.Tune,
                    label = "Filter",
                    active = ui.premiumFilter,
                    onClick = { vm.setPremiumFilter(!ui.premiumFilter) }
                )
            }
            Spacer(Modifier.height(4.dp))

            // ---- Settings ----
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingRow(label = "Aspect ratio") {
                    SegmentedMini(
                        options = AspectRatio.values().map { it.label },
                        selected = ui.aspectRatio.label,
                        onSelect = { label ->
                            vm.setAspectRatio(AspectRatio.values().first { it.label == label })
                        }
                    )
                }
                SettingRow(label = "Resolution") {
                    SegmentedMini(
                        options = Resolution.values().map { it.label },
                        selected = ui.resolution.label,
                        onSelect = { label ->
                            vm.setResolution(Resolution.values().first { it.label == label })
                        }
                    )
                }
                // Toggles (AUTO-ON by default)
                ToggleRowMini(
                    title = "Noise cancellation",
                    subtitle = "RNNoise AI + FFT denoiser",
                    checked = ui.noiseReduction,
                    onChange = vm::setNoiseReduction
                )
                ToggleRowMini(
                    title = "Premium filter",
                    subtitle = "Contrast & saturation grade",
                    checked = ui.premiumFilter,
                    onChange = vm::setPremiumFilter
                )
            }

            // ---- Error card ----
            ui.error?.let { err ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1212)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            err,
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = vm::clearError, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Color.White)
                        }
                    }
                }
            }

            // ---- Output card ----
            ui.outputFile?.let { file ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF122A12)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF7FD67F))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            file.name,
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { downloadFile(context, file) }) {
                            Icon(Icons.Filled.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save", color = Color.White, fontSize = 12.sp)
                        }
                        TextButton(onClick = { shareFile(context, file) }) {
                            Icon(Icons.Filled.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Share", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }

            // ---- Progress ----
            if (ui.isBuilding) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        "Building… ${ui.progress}%",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = ui.progress / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color.White,
                        trackColor = Color(0xFF2A2A2A)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = vm::cancelBuild,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) { Text("Cancel", fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- Build button (fixed at bottom) ----
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Button(
                onClick = vm::build,
                enabled = ui.clips.isNotEmpty() && !ui.isBuilding,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    disabledContainerColor = Color(0xFF2A2A2A),
                    disabledContentColor = Color(0xFF666666)
                ),
                shape = RoundedCornerShape(15.dp)
            ) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text("BUILD VIDEO", fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun ToolIcon(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (active) Color.White else Color(0xFF1D1D1D)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) Color.Black else Color(0xFFCCCCCC),
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 9.sp, color = Color(0xFF777777), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingRow(
    label: String,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun SegmentedMini(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF171717))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        options.forEach { opt ->
            val isSel = opt == selected
            Text(
                opt,
                fontSize = 10.5.sp,
                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                color = if (isSel) Color.Black else Color(0xFF777777),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSel) Color.White else Color.Transparent)
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 11.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun ToggleRowMini(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFF666666), fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color.White,
                uncheckedThumbColor = Color(0xFF777777),
                uncheckedTrackColor = Color(0xFF2C2C2C)
            ),
            modifier = Modifier.size(width = 42.dp, height = 24.dp)
        )
    }
}

@Composable
private fun TimelineThumb(
    clip: ClipItem,
    index: Int,
    selected: Boolean,
    onRemove: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 88.dp, height = 64.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0xFF181818))
    ) {
        ClipThumbnail(
            uri = clip.uri,
            modifier = Modifier.fillMaxSize()
        )
        // Number badge
        Text(
            "${index + 1}",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(5.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 7.dp, vertical = 1.dp)
        )
        // Duration badge
        Text(
            formatDuration(clip.durationMs),
            fontSize = 9.sp,
            color = Color(0xFFAAAAAA),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 6.dp, vertical = 1.dp)
        )
        // Remove button
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove",
                tint = Color.White,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun ClipThumbnail(
    uri: android.net.Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var thumbnail by remember(uri) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    LaunchedEffect(uri) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
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
    if (thumbnail != null) {
        Image(
            bitmap = thumbnail!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(Color(0xFF1A1A1A)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color(0xFF444444),
                modifier = Modifier.size(24.dp)
            )
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

package com.shiturmedia.compressor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

// ─── Colors ─────────────────────────────────────────────────────────────────────
private val BgDark      = Color(0xFF121217)
private val SurfaceDark = Color(0xFF1E1E26)
private val Accent      = Color(0xFF7C3AED)
private val AccentLight = Color(0xFF9D5FF7)
private val TextPrimary = Color(0xFFEAEAF0)
private val TextSecond  = Color(0xFF8888AA)
private val BorderColor = Color(0xFF2E2E3A)

// ─── MainActivity ────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShitUrMediaTheme {
                ShitUrMediaApp()
            }
        }
    }
}

// ─── Theme ───────────────────────────────────────────────────────────────────────
@Composable
fun ShitUrMediaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary      = Accent,
            onPrimary    = Color.White,
            background   = BgDark,
            surface      = SurfaceDark,
            onBackground = TextPrimary,
            onSurface    = TextPrimary,
        ),
        content = content
    )
}

// ─── Root App Composable ─────────────────────────────────────────────────────────
@Composable
fun ShitUrMediaApp() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // ── Core state ──
    var selectedUri     by remember { mutableStateOf<Uri?>(null) }
    var selectedPreset  by remember { mutableStateOf(CompressionPreset.DISCORD_8MB) }
    var isCompressing   by remember { mutableStateOf(false) }
    var progress        by remember { mutableStateOf(0f) }
    var statusMessage   by remember { mutableStateOf("") }
    var outputVideoFile by remember { mutableStateOf<File?>(null) }

    // ── Advanced settings state ──
    var showAdvanced      by remember { mutableStateOf(false) }
    var customVideoEnabled by remember { mutableStateOf(false) }
    var customVideoKbps   by remember { mutableStateOf(500f) }
    var customAudioEnabled by remember { mutableStateOf(false) }
    var customAudioKbps   by remember { mutableStateOf(128f) }
    var isBlackAndWhite   by remember { mutableStateOf(false) }
    var isFlipped         by remember { mutableStateOf(false) }
    var isMuted           by remember { mutableStateOf(false) }
    var originalFps       by remember { mutableStateOf(30f) }
    var customFps         by remember { mutableStateOf(30f) }

    val snackbarHostState = remember { SnackbarHostState() }
    val animatedProgress  by animateFloatAsState(targetValue = progress, label = "progress")

    // ── Launchers ──
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled by check below */ }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedUri    = uri
            outputVideoFile = null
            statusMessage  = ""
            progress       = 0f

            // ── Detect source FPS via MediaExtractor (no coroutine needed) ──
            try {
                val extractor = android.media.MediaExtractor()
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime   = format.getString(android.media.MediaFormat.KEY_MIME)
                    if (mime?.startsWith("video/") == true) {
                        if (format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                            val detected = format.getInteger(android.media.MediaFormat.KEY_FRAME_RATE).toFloat()
                            originalFps  = if (detected > 0f) detected else 30f
                        }
                        break
                    }
                }
                extractor.release()
            } catch (e: Exception) {
                originalFps = 30f
            }
            customFps = originalFps   // reset slider to source FPS on each new pick
        }
    }

    Scaffold(
        snackbarHost   = { SnackbarHost(snackbarHostState) },
        containerColor = BgDark
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Header ───────────────────────────────────────────────────────
            AppHeader()

            // ── Video Drop Zone ───────────────────────────────────────────────
            VideoDropZone(
                uri     = selectedUri,
                context = context,
                onClick = {
                    if (!hasMediaPermission(context)) requestPermissions(context, permissionLauncher)
                    videoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                }
            )

            // ── Preset Selector ───────────────────────────────────────────────
            PresetSelector(
                selected  = selectedPreset,
                onSelect  = { selectedPreset = it },
                enabled   = !isCompressing
            )

            // ── Advanced Settings ─────────────────────────────────────────────
            AdvancedSettingsSection(
                expanded           = showAdvanced,
                onToggle           = { showAdvanced = !showAdvanced },
                customVideoEnabled = customVideoEnabled,
                onVideoEnabledChange = { customVideoEnabled = it },
                customVideoKbps    = customVideoKbps,
                onVideoKbpsChange  = { customVideoKbps = it },
                customAudioEnabled = customAudioEnabled,
                onAudioEnabledChange = { customAudioEnabled = it },
                customAudioKbps    = customAudioKbps,
                onAudioKbpsChange  = { customAudioKbps = it },
                isBlackAndWhite    = isBlackAndWhite,
                onBwChange         = { isBlackAndWhite = it },
                isFlipped          = isFlipped,
                onFlipChange       = { isFlipped = it },
                isMuted            = isMuted,
                onMuteChange       = { isMuted = it },
                originalFps        = originalFps,
                customFps          = customFps,
                onFpsChange        = { customFps = it },
                enabled            = !isCompressing
            )

            // ── Progress / Status ─────────────────────────────────────────────
            AnimatedVisibility(
                visible = isCompressing || statusMessage.isNotBlank(),
                enter   = fadeIn() + slideInVertically(),
                exit    = fadeOut()
            ) {
                ProgressSection(
                    progress      = animatedProgress,
                    statusMessage = statusMessage,
                    isCompressing = isCompressing
                )
            }

            // ── In-App Video Player ───────────────────────────────────────────
            AnimatedVisibility(
                visible = outputVideoFile != null,
                enter   = fadeIn() + slideInVertically(),
                exit    = fadeOut()
            ) {
                outputVideoFile?.let { file ->
                    VideoPlayerSection(file = file)
                }
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))

            // ── Compress Button ───────────────────────────────────────────────
            CompressButton(
                enabled = selectedUri != null && !isCompressing,
                onClick = {
                    val uri = selectedUri ?: return@CompressButton
                    scope.launch {
                        isCompressing   = true
                        progress        = 0f
                        statusMessage   = "Başlatılıyor…"
                        outputVideoFile = null

                        val resultFile = CompressorEngine.compress(
                            context         = context,
                            inputUri        = uri,
                            preset          = selectedPreset,
                            customVideoKbps = if (customVideoEnabled) customVideoKbps.roundToInt() else null,
                            customAudioKbps = if (customAudioEnabled) customAudioKbps.roundToInt() else null,
                            customFps       = customFps.roundToInt().takeIf { it < originalFps.roundToInt() },
                            isBlackAndWhite = isBlackAndWhite,
                            isFlipped       = isFlipped,
                            isMuted         = isMuted,
                            onProgress      = { msg ->
                                statusMessage = msg
                                // Extract numeric percentage from strings like "İşleniyor… 45%"
                                val pct = Regex("(\\d+)%").find(msg)
                                    ?.groupValues?.get(1)?.toFloatOrNull()
                                if (pct != null) progress = pct / 100f
                            }
                        )

                        isCompressing = false

                        if (resultFile != null) {
                            progress       = 1f
                            outputVideoFile = resultFile
                            val sizeMB = "%.2f".format(resultFile.length() / 1_048_576.0)
                            statusMessage  = "✅ Tamamlandı! Boyut: $sizeMB MB"
                            snackbarHostState.showSnackbar(
                                message = "Sıkıştırma tamamlandı – $sizeMB MB"
                            )
                        } else {
                            progress = 0f
                        }
                    }
                }
            )
        }
    }
}

// ─── Header ──────────────────────────────────────────────────────────────────────
@Composable
private fun AppHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text       = "💩 Shit Ur Media",
            fontSize   = 28.sp,
            fontWeight = FontWeight.Bold,
            color      = TextPrimary
        )
        Text(
            text          = "Compressor",
            fontSize      = 16.sp,
            color         = Accent,
            fontWeight    = FontWeight.SemiBold,
            letterSpacing = 3.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(text = "Video'nu ezip geri dön.", fontSize = 12.sp, color = TextSecond)
    }
}

// ─── Video Drop Zone ──────────────────────────────────────────────────────────────
@Composable
private fun VideoDropZone(uri: Uri?, context: Context, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(shape)
            .background(SurfaceDark)
            .border(
                width = 1.5.dp,
                brush = if (uri != null)
                    Brush.linearGradient(listOf(Accent, AccentLight))
                else
                    Brush.linearGradient(listOf(BorderColor, BorderColor)),
                shape = shape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (uri != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(uri)
                    .decoderFactory { result, options, _ -> VideoFrameDecoder(result.source, options) }
                    .crossfade(true)
                    .build(),
                contentDescription = "Video önizleme",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint     = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Video seçildi", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Değiştirmek için tıkla", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    VideoInfoRow(uri, context)
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Accent, modifier = Modifier.size(52.dp))
                Spacer(Modifier.height(12.dp))
                Text("Video Seç", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Galeriden bir video seçmek için dokun",
                    color     = TextSecond,
                    fontSize  = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun VideoInfoRow(uri: Uri, context: Context) {
    val info = remember(uri) { getVideoInfo(context, uri) }
    if (info.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.5f)) {
            Text(
                text     = info,
                color    = Color.White.copy(alpha = 0.9f),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

private fun getVideoInfo(context: Context, uri: Uri): String = try {
    MediaMetadataRetriever().use { r ->
        r.setDataSource(context, uri)
        val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val width      = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val height     = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val sizeBytes  = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        "${durationMs / 1000}s  •  ${width}x${height}  •  ${"%.1f".format(sizeBytes / 1_048_576.0)} MB"
    }
} catch (e: Exception) { "" }

// ─── Preset Selector ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSelector(
    selected: CompressionPreset,
    onSelect: (CompressionPreset) -> Unit,
    enabled: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(16.dp)
    ) {
        Text("Preset Seç", color = TextSecond, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompressionPreset.entries.forEach { preset ->
                FilterChip(
                    selected = preset == selected,
                    onClick  = { if (enabled) onSelect(preset) },
                    label    = { Text(preset.label, fontSize = 11.sp, maxLines = 1) },
                    modifier = Modifier.weight(1f),
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent,
                        selectedLabelColor     = Color.White,
                        containerColor         = Color(0xFF16161E),
                        labelColor             = TextSecond
                    )
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(text = presetDescription(selected), color = TextSecond, fontSize = 11.sp)
    }
}

private fun presetDescription(preset: CompressionPreset) = when (preset) {
    CompressionPreset.DISCORD_8MB  -> "Discord ücretsiz plan — 8 MB sınırı için optimize edildi."
    CompressionPreset.DISCORD_25MB -> "Discord Nitro — 25 MB sınırı için optimize edildi."
    CompressionPreset.POTATO_144P  -> "144p kare patates kalitesi. Earrape ses. Meme amacıyla."
    CompressionPreset.NOKIA_3GP    -> "176×144 @ 12 kbps mono. Gerçek Nokia 3310 titreşimi."
}

// ─── Advanced Settings Section ────────────────────────────────────────────────────
@Composable
private fun AdvancedSettingsSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    customVideoEnabled: Boolean,
    onVideoEnabledChange: (Boolean) -> Unit,
    customVideoKbps: Float,
    onVideoKbpsChange: (Float) -> Unit,
    customAudioEnabled: Boolean,
    onAudioEnabledChange: (Boolean) -> Unit,
    customAudioKbps: Float,
    onAudioKbpsChange: (Float) -> Unit,
    isBlackAndWhite: Boolean,
    onBwChange: (Boolean) -> Unit,
    isFlipped: Boolean,
    onFlipChange: (Boolean) -> Unit,
    isMuted: Boolean,
    onMuteChange: (Boolean) -> Unit,
    originalFps: Float,
    customFps: Float,
    onFpsChange: (Float) -> Unit,
    enabled: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
    ) {
        // ── Collapse header ───────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment      = Alignment.CenterVertically,
            horizontalArrangement  = Arrangement.SpaceBetween
        ) {
            Text("Gelişmiş Ayarlar", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Icon(
                imageVector        = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint               = TextSecond
            )
        }

        // ── Expandable content ────────────────────────────────────────────────
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HorizontalDivider(color = BorderColor)
                Spacer(Modifier.height(2.dp))

                // ── FPS Slider (max = detected source FPS) ────────────────────
                val fpsMax = originalFps.coerceAtLeast(1f)
                Column {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Kare Hızı (FPS)", color = TextPrimary, fontSize = 13.sp)
                        Text(
                            "${customFps.roundToInt()} fps  /  ${originalFps.roundToInt()} kaynak",
                            color    = if (customFps < originalFps) AccentLight else TextSecond,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Slider(
                        value         = customFps.coerceIn(1f, fpsMax),
                        onValueChange = { onFpsChange(it) },
                        valueRange    = 1f..fpsMax,
                        steps         = (fpsMax - 1f).toInt().coerceAtLeast(0),
                        enabled       = enabled,
                        colors        = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                        modifier      = Modifier.fillMaxWidth()
                    )
                }

                // ── Video Bitrate slider ──────────────────────────────────────
                BitrateSlider(
                    label         = "Video Bitrate",
                    unit          = "kbps",
                    value         = customVideoKbps,
                    valueRange    = 50f..5000f,
                    enabled       = enabled,
                    switchEnabled = customVideoEnabled,
                    onSwitchChange = onVideoEnabledChange,
                    onValueChange  = onVideoKbpsChange
                )

                // ── Audio Bitrate slider ──────────────────────────────────────
                BitrateSlider(
                    label         = "Ses Bitrate",
                    unit          = "kbps",
                    value         = customAudioKbps,
                    valueRange    = 8f..320f,
                    enabled       = enabled,
                    switchEnabled = customAudioEnabled,
                    onSwitchChange = onAudioEnabledChange,
                    onValueChange  = onAudioKbpsChange
                )

                HorizontalDivider(color = BorderColor)

                // ── Effect toggles ────────────────────────────────────────────
                Text("Video Efektleri", color = TextSecond, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)

                EffectToggleRow(label = "Siyah Beyaz",    checked = isBlackAndWhite, onCheckedChange = onBwChange,   enabled = enabled)
                EffectToggleRow(label = "Aynala (Yatay)", checked = isFlipped,       onCheckedChange = onFlipChange, enabled = enabled)
                EffectToggleRow(label = "Sesi Kapat",     checked = isMuted,         onCheckedChange = onMuteChange, enabled = enabled)
            }
        }
    }
}

@Composable
private fun BitrateSlider(
    label: String,
    unit: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    switchEnabled: Boolean,
    onSwitchChange: (Boolean) -> Unit,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextPrimary, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (switchEnabled) {
                    Text(
                        "${value.roundToInt()} $unit",
                        color    = AccentLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text("Varsayılan", color = TextSecond, fontSize = 12.sp)
                }
                Switch(
                    checked         = switchEnabled,
                    onCheckedChange = onSwitchChange,
                    enabled         = enabled,
                    colors          = SwitchDefaults.colors(checkedTrackColor = Accent)
                )
            }
        }
        if (switchEnabled) {
            Slider(
                value         = value,
                onValueChange = onValueChange,
                valueRange    = valueRange,
                enabled       = enabled && switchEnabled,
                colors        = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                modifier      = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EffectToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextPrimary, fontSize = 13.sp)
        Switch(
            checked         = checked,
            onCheckedChange = onCheckedChange,
            enabled         = enabled,
            colors          = SwitchDefaults.colors(checkedTrackColor = Accent)
        )
    }
}

// ─── In-App Video Player ──────────────────────────────────────────────────────────
@Composable
private fun VideoPlayerSection(file: File) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Çıktı Videosu",
            color      = TextSecond,
            fontSize   = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
        AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    setVideoPath(file.absolutePath)
                    val mc = android.widget.MediaController(ctx)
                    mc.setAnchorView(this)
                    setMediaController(mc)
                    requestFocus()
                    start()
                }
            },
            update = { videoView ->
                // Re-bind if the file reference changes (e.g. new compression)
                if (videoView.tag != file.absolutePath) {
                    videoView.tag = file.absolutePath
                    videoView.setVideoPath(file.absolutePath)
                    videoView.start()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
        )
        Text(
            text     = "Boyut: ${"%.2f".format(file.length() / 1_048_576.0)} MB  •  ${file.name}",
            color    = TextSecond,
            fontSize = 11.sp
        )

        // ── Action Buttons ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    val savedUri = saveVideoToGallery(context, file)
                    if (savedUri != null) {
                        Toast.makeText(context, "Video Galeriye Kaydedildi!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Galeriye kaydedilemedi.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = "Galeriye Kaydet",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            OutlinedButton(
                onClick = {
                    shareVideo(context, file)
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AccentLight),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AccentLight
                )
            ) {
                Text(
                    text = "Paylaş",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ─── Progress Section ─────────────────────────────────────────────────────────────
@Composable
private fun ProgressSection(progress: Float, statusMessage: String, isCompressing: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(statusMessage, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (isCompressing) {
            LinearProgressIndicator(
                progress   = { progress },
                modifier   = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color      = Accent,
                trackColor = BorderColor,
            )
            Text("${(progress * 100).toInt()}%", color = TextSecond, fontSize = 12.sp)
        }
    }
}

// ─── Compress Button ──────────────────────────────────────────────────────────────
@Composable
private fun CompressButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick  = onClick,
        enabled  = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        shape  = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor         = Accent,
            contentColor           = Color.White,
            disabledContainerColor = SurfaceDark,
            disabledContentColor   = TextSecond
        )
    ) {
        Text("MEDYAYI SIKIŞTIR 💀", fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────────
private fun hasMediaPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    else
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

private fun requestPermissions(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    launcher.launch(perms)
}

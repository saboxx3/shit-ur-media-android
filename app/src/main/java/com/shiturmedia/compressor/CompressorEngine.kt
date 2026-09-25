package com.shiturmedia.compressor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

// ─── Compression Preset ────────────────────────────────────────────────────────
enum class CompressionPreset(val label: String) {
    DISCORD_8MB("Discord 8MB"),
    DISCORD_25MB("Discord 25MB"),
    POTATO_144P("144p Patates"),
    NOKIA_3GP("Nokia 3GP")
}

// ─── Engine ────────────────────────────────────────────────────────────────────
object CompressorEngine {

    private const val TAG = "FFMPEG_EXEC"

    // Try hardware H.264 first; fall back to mpeg4 (always present in any FFmpegKit build).
    // Nokia 3GP skips h264_mediacodec — mpeg4 is more compatible with the 3GP format.
    private val ENCODER_CHAIN       = listOf("h264_mediacodec", "mpeg4")
    private val NOKIA_ENCODER_CHAIN = listOf("mpeg4")

    /**
     * Compress [inputUri] and return the resulting local [File], or null on failure.
     *
     * @param customVideoKbps  Override calculated video bitrate (kbps). null = use preset default.
     * @param customAudioKbps  Override audio bitrate (kbps). null = use preset default.
     * @param isBlackAndWhite  Append `format=gray` to the video filter chain.
     * @param isFlipped        Append `hflip` to the video filter chain.
     * @param isMuted          Strip all audio; adds `-an`.
     * @param onProgress       Receives human-readable status strings (e.g. "İşleniyor… 45%").
     */
    suspend fun compress(
        context: Context,
        inputUri: Uri,
        preset: CompressionPreset,
        customVideoKbps: Int?    = null,
        customAudioKbps: Int?    = null,
        customFps: Int?          = null,
        isBlackAndWhite: Boolean = false,
        isFlipped: Boolean       = false,
        isMuted: Boolean         = false,
        onProgress: (String) -> Unit
    ): File? = withContext(Dispatchers.IO) {

        // ── 1. Copy content URI → local temp file ─────────────────────────────
        val tempInput = File(context.cacheDir, "sumc_in_${System.currentTimeMillis()}.mp4")
        try {
            context.contentResolver.openInputStream(inputUri)?.use { src ->
                tempInput.outputStream().use { dst -> src.copyTo(dst) }
            } ?: run {
                onProgress("Hata: girdi akışı açılamadı.")
                return@withContext null
            }
        } catch (e: Exception) {
            tempInput.delete()
            onProgress("Hata: ${e.message}")
            return@withContext null
        }

        // ── 2. Duration ───────────────────────────────────────────────────────
        val durationSec: Double
        try {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, inputUri)
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                durationSec = (ms / 1000.0).coerceAtLeast(1.0)
            }
        } catch (e: Exception) {
            tempInput.delete()
            onProgress("Hata: metadata okunamadı — ${e.message}")
            return@withContext null
        }

        // ── 3. Output file in cache ───────────────────────────────────────────
        val outputName = "sumc_${preset.name.lowercase()}_${System.currentTimeMillis()}.mp4"
        val cacheOutput = File(context.cacheDir, outputName)

        onProgress("Hazırlanıyor… 5%")

        // ── 4. Try each encoder in chain ──────────────────────────────────────
        val chain = if (preset == CompressionPreset.NOKIA_3GP) NOKIA_ENCODER_CHAIN else ENCODER_CHAIN
        var succeeded = false

        try {
            for (encoder in chain) {
                cacheOutput.delete()

                val args = buildArgs(
                    input           = tempInput,
                    output          = cacheOutput,
                    preset          = preset,
                    durationSec     = durationSec,
                    encoder         = encoder,
                    customVideoKbps = customVideoKbps,
                    customAudioKbps = customAudioKbps,
                    customFps       = customFps,
                    isBlackAndWhite = isBlackAndWhite,
                    isFlipped       = isFlipped,
                    isMuted         = isMuted
                )

                Log.d(TAG, "encoder=$encoder  args: ${args.joinToString(" ")}")
                onProgress("Kodlanıyor ($encoder)…")

                val (ok, log) = runFfmpeg(args, durationSec) { fraction ->
                    onProgress("İşleniyor… ${(fraction * 100).toInt()}%")
                }

                if (ok && cacheOutput.exists()) {
                    Log.d(TAG, "✓ encoder=$encoder succeeded")
                    succeeded = true
                    break
                }
                Log.w(TAG, "✗ encoder=$encoder failed:\n${log.lines().takeLast(6).joinToString("\n")}")
            }
        } finally {
            tempInput.delete()
        }

        if (!succeeded || !cacheOutput.exists()) {
            cacheOutput.delete()
            onProgress("Hata: FFmpeg tüm encoder'larla başarısız oldu.")
            return@withContext null
        }

        onProgress("Tamamlandı! ✅")
        cacheOutput
    }

    // ─── FFmpeg Coroutine Runner ──────────────────────────────────────────────
    private suspend fun runFfmpeg(
        args: Array<String>,
        durationSec: Double,
        onProgress: (Float) -> Unit
    ): Pair<Boolean, String> = suspendCancellableCoroutine { cont ->
        var log = ""
        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            { completed ->
                log = completed.allLogsAsString ?: completed.failStackTrace ?: ""
                cont.resume(ReturnCode.isSuccess(completed.returnCode) to log)
            },
            { /* per-line log – captured in bulk on completion */ },
            { stats ->
                val p = (stats.time / (durationSec * 1000.0)).toFloat().coerceIn(0.05f, 0.95f)
                onProgress(p)
            }
        )
        cont.invokeOnCancellation { session?.cancel() }
    }

    // ─── Dynamic Argument Builder ─────────────────────────────────────────────
    //
    //  Filter chain order (performance-critical):
    //    1. fps=N       → drop excess frames FIRST so every downstream filter
    //                     only processes frames that will actually be kept.
    //    2. scale=...   → resize on the already-trimmed frame set.
    //    3. format=gray → colour conversion after resize (fewer pixels).
    //    4. hflip       → purely geometric, negligible cost, goes last.
    //
    //  Other rules:
    //   • NO -preset  — not supported by bundled FFmpegKit binary.
    //   • NO -crf     — hardware encoders (h264_mediacodec) only accept -b:v.
    //   • -pix_fmt yuv420p — required by HW encoder + Android HW decoders.
    //
    private fun buildArgs(
        input: File,
        output: File,
        preset: CompressionPreset,
        durationSec: Double,
        encoder: String,
        customVideoKbps: Int?,
        customAudioKbps: Int?,
        customFps: Int?,
        isBlackAndWhite: Boolean,
        isFlipped: Boolean,
        isMuted: Boolean
    ): Array<String> {

        val args = mutableListOf<String>()
        args += listOf("-y", "-i", input.absolutePath)

        // ── 1. Build video filter chain ────────────────────────────────────
        val vfFilters = mutableListOf<String>()

        // Step 1 — FPS decimation (always first — drop frames before any resize work)
        val defaultFps = when (preset) {
            CompressionPreset.POTATO_144P -> 15
            CompressionPreset.NOKIA_3GP   -> 12
            else                          -> null   // Discord presets keep source FPS unless overridden
        }
        val targetFps = customFps ?: defaultFps
        if (targetFps != null) vfFilters += "fps=$targetFps"

        // Step 2 — Scale (preset-specific, after frames have already been dropped)
        val baseVideoBr: Int
        val useRateLimits: Boolean
        when (preset) {
            CompressionPreset.DISCORD_8MB -> {
                baseVideoBr   = ((8.0  * 8192) / durationSec - 64).toInt().coerceAtLeast(100)
                useRateLimits = true
                vfFilters    += "scale=trunc(iw/2)*2:trunc(ih/2)*2"
            }
            CompressionPreset.DISCORD_25MB -> {
                baseVideoBr   = ((25.0 * 8192) / durationSec - 128).toInt().coerceAtLeast(200)
                useRateLimits = true
                vfFilters    += "scale=trunc(iw/2)*2:trunc(ih/2)*2"
            }
            CompressionPreset.POTATO_144P -> {
                baseVideoBr   = 150
                useRateLimits = false
                // Nearest-neighbour downscale → chunky pixel-art look, then ensure even dims
                vfFilters    += "scale=256:144:flags=neighbor"
                vfFilters    += "scale=trunc(iw/2)*2:trunc(ih/2)*2"
            }
            CompressionPreset.NOKIA_3GP -> {
                baseVideoBr   = 80
                useRateLimits = false
                vfFilters    += "scale=176:144"   // fixed CIF-quarter — 176 & 144 already even
            }
        }

        // Step 3 — Colour / geometry effects (after resize = fewer pixels to process)
        if (isBlackAndWhite) vfFilters += "format=gray"
        if (isFlipped)       vfFilters += "hflip"

        // Emit -vf only when there is at least one filter
        if (vfFilters.isNotEmpty()) {
            args += listOf("-vf", vfFilters.joinToString(","))
        }

        // ── 2. Video codec & bitrate ───────────────────────────────────────
        val videoBr = customVideoKbps ?: baseVideoBr
        args += listOf("-c:v", encoder)
        args += listOf("-b:v", "${videoBr}k")
        if (useRateLimits) {
            args += listOf("-maxrate", "${(videoBr * 1.5).toInt()}k")
            args += listOf("-bufsize", "${(videoBr * 2).toInt()}k")
        }
        args += listOf("-pix_fmt", "yuv420p")

        // ── 3. Audio ───────────────────────────────────────────────────────
        if (isMuted) {
            args += "-an"
        } else {
            args += listOf("-c:a", "aac")
            when (preset) {
                CompressionPreset.DISCORD_8MB ->
                    args += listOf("-b:a", "${customAudioKbps ?: 64}k")
                CompressionPreset.DISCORD_25MB ->
                    args += listOf("-b:a", "${customAudioKbps ?: 128}k")
                CompressionPreset.POTATO_144P -> {
                    args += listOf("-b:a", "${customAudioKbps ?: 32}k")
                    args += listOf("-af", "aresample=8000,acrusher=bits=8:mode=log:aa=1")
                    args += listOf("-ac", "1")
                    args += listOf("-ar", "8000")
                }
                CompressionPreset.NOKIA_3GP -> {
                    args += listOf("-b:a", "${customAudioKbps ?: 12}k")
                    args += listOf("-ac", "1")
                    args += listOf("-ar", "8000")
                }
            }
        }

        args += output.absolutePath
        return args.toTypedArray()
    }
}

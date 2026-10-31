// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewOutlineProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Visible Activity only. Call bindPreview(), then recordNote() from a coroutine.
 * CameraX captures a ten-second source; Media3 exports a square H.264 MP4 with
 * black corners outside the circle. H.264 does not provide transparent corners.
 * A successful return is verified to be <3,000,000 bytes. Failure throws.
 * Close is terminal and is automatic on ON_STOP; recreate on resume.
 */
@UnstableApi
class VideoNoteEngine(
    context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView
) : Closeable {
    data class Note(val file: File, val durationMs: Long, val bytes: Long)
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { command -> main.post(command) }
    private val lock = Mutex()
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var capture: VideoCapture<Recorder>? = null
    private var activeJob: Job? = null
    private var closed = false
    private val oldOutline = previewView.outlineProvider
    private val oldClip = previewView.clipToOutline
    private val oldScale = previewView.scaleType
    private val oldImplementation = previewView.implementationMode
    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) close()
    }

    init {
        checkMain()
        check(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        owner.lifecycle.addObserver(observer)
    }

    /** Host layout must give PreviewView equal width/height for a circular preview. */
    suspend fun bindPreview(selector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA) =
        withContext(Dispatchers.Main.immediate) {
            checkActive()
            checkPermission(Manifest.permission.CAMERA)
            check(capture == null) { "Preview already bound" }
            val future = ProcessCameraProvider.getInstance(app)
            val cameraProvider = suspendCancellableCoroutine<ProcessCameraProvider> { continuation ->
                future.addListener({
                    try { if (continuation.isActive) continuation.resume(future.get()) }
                    catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }, executor)
            }
            checkActive()
            check(capture == null) { "Concurrent bindPreview calls" }
            val p = Preview.Builder().build()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.SD, Quality.HD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .setTargetVideoEncodingBitRate(2_000_000).build()
            val video = VideoCapture.withOutput(recorder)
            // TextureView supports reliable clipping; SurfaceView may ignore outline clipping.
            previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
            previewView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val side = minOf(view.width, view.height)
                    val x = (view.width - side) / 2
                    val y = (view.height - side) / 2
                    outline.setOval(x, y, x + side, y + side)
                }
            }
            previewView.clipToOutline = true
            p.setSurfaceProvider(previewView.surfaceProvider)
            cameraProvider.bindToLifecycle(owner, selector, p, video)
            provider = cameraProvider; preview = p; capture = video
        }

    /** Serial operation. Stop PTT before requesting video audio (shared microphone). */
    suspend fun recordNote(withAudio: Boolean = true): Note {
        var produced: File? = null
        try {
            return withContext(Dispatchers.Main.immediate) {
                checkActive()
                check(lock.tryLock()) { "A video note is already in progress" }
                try {
                    coroutineScope {
                        activeJob = currentCoroutineContext()[Job]
                        try {
                            val source = captureRaw(withAudio)
                            try {
                                for (bitrate in intArrayOf(1_600_000, 1_000_000, 640_000)) {
                                    ensureActive()
                                    val output = File.createTempFile("mesh-note-", ".mp4", app.cacheDir)
                                    var accepted = false
                                    try {
                                        export(source, output, bitrate)
                                        val duration = withContext(Dispatchers.IO) { verifyVideo(output) }
                                        if (output.length() in 1 until MAX_BYTES) {
                                            accepted = true
                                            produced = output
                                            return@coroutineScope Note(output, duration, output.length())
                                        }
                                    } finally { if (!accepted) output.delete() }
                                }
                                error("Encoder could not meet the video note size limit")
                            } finally { source.delete() }
                        } finally { activeJob = null }
                    }
                } finally { lock.unlock() }
            }
        } catch (e: Throwable) {
            // Includes prompt cancellation while dispatching a successful result to the caller.
            produced?.delete()
            throw e
        }
    }

    private suspend fun captureRaw(withAudio: Boolean): File {
        checkActive()
        checkPermission(Manifest.permission.CAMERA)
        if (withAudio) checkPermission(Manifest.permission.RECORD_AUDIO)
        val video = checkNotNull(capture) { "Call bindPreview first" }
        val raw = File.createTempFile("mesh-note-source-", ".mp4", app.cacheDir)
        val finished = CompletableDeferred<VideoRecordEvent.Finalize>()
        var recording: Recording? = null
        var success = false
        try {
            val options = FileOutputOptions.Builder(raw).setDurationLimitMillis(10_000).build()
            var pending = video.output.prepareRecording(app, options)
            if (withAudio) pending = pending.withAudioEnabled()
            recording = pending.start(executor) { event ->
                if (event is VideoRecordEvent.Finalize) finished.complete(event)
            }
            val event = withTimeout(20_000) { finished.await() }
            check(!event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) {
                "Camera recording failed (${event.error}): ${event.cause}"
            }
            check(event.recordingStats.recordedDurationNanos >= 9_800_000_000L) { "Recording ended early" }
            success = true
            return raw
        } finally {
            recording?.close()
            if (!success) {
                // Finalize owns the file until CameraX closes its writer.
                if (recording == null || finished.isCompleted) raw.delete()
                else finished.invokeOnCompletion { raw.delete() }
            }
        }
    }

    private suspend fun export(source: File, output: File, bitrate: Int) {
        val mask = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        Canvas(mask).apply {
            drawColor(Color.BLACK)
            drawCircle(SIDE / 2f, SIDE / 2f, SIDE / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        }
        var transformer: Transformer? = null
        try {
            val item = MediaItem.Builder().setUri(android.net.Uri.fromFile(source))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(10_000).build()).build()
            val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), listOf(
                Presentation.createForWidthAndHeight(SIDE, SIDE, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(mask)))
            ))).build()
            val factory = DefaultEncoderFactory.Builder(app)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(64_000).build())
                .setEnableFallback(true).build()
            val finished = CompletableDeferred<Unit>()
            val exporter = Transformer.Builder(app).setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC).setEncoderFactory(factory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished.complete(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exception: ExportException) {
                        finished.completeExceptionally(exception)
                    }
                }).build().also { transformer = it }
            exporter.start(edited, output.absolutePath)
            withTimeout(120_000) { finished.await() }
        } finally {
            transformer?.cancel() // Main looper; stops export before cleanup/recycling.
            mask.recycle()
        }
    }

    private fun verifyVideo(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val video = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .single { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            check(video.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC)
            check(video.getInteger(MediaFormat.KEY_WIDTH) == SIDE && video.getInteger(MediaFormat.KEY_HEIGHT) == SIDE)
        } finally { extractor.release() }
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.absolutePath)
            val duration = checkNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
            check(duration in 9_800..10_100) { "Unexpected duration: $duration ms" }
            return duration
        } finally { metadata.release() }
    }

    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        owner.lifecycle.removeObserver(observer)
        activeJob?.cancel()
        preview?.let { provider?.unbind(it) }
        capture?.let { provider?.unbind(it) }
        previewView.outlineProvider = oldOutline
        previewView.clipToOutline = oldClip
        previewView.scaleType = oldScale
        previewView.implementationMode = oldImplementation
        preview = null; capture = null; provider = null
    }

    private fun checkActive() {
        checkMain()
        check(!closed && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    private fun checkPermission(permission: String) =
        check(app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) { "Grant $permission first" }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "Call on main thread" }

    companion object {
        const val MAX_BYTES = 3_000_000L
        private const val SIDE = 480
    }
}

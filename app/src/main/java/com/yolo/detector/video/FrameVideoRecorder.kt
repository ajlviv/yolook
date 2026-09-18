package com.yolo.detector.video

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encodes the frames the Live screen displays into an H.264 MP4 using a
 * [MediaCodec] surface encoder + [MediaMuxer].
 *
 * The caller draws the exact recorded frame onto the encoder's input surface
 * through [present], so recording captures what is on screen (view-mode
 * filters and the bounding-box overlay are baked in by the caller). The output
 * dimensions are fixed at construction and match the screen aspect, so the
 * caller draws at native size with no scaling.
 *
 * The caller keeps ownership of every bitmap it draws — this class only reads
 * pixels while rendering to the input surface.
 */
class FrameVideoRecorder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val output: ParcelFileDescriptor,
) {
    companion object {
        private const val TAG = "FrameVideoRecorder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val FINALIZE_TIMEOUT_MS = 10_000L
    }

    private val drainExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(drainExecutor.asCoroutineDispatcher() + SupervisorJob())
    private val codec = MediaCodec.createEncoderByType(MIME)
    private val muxer = MediaMuxer(output.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    private var inputSurface: Surface? = null
    private var trackIndex = -1
    private var muxerStarted = false

    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val drained = CompletableDeferred<Boolean>()

    /**
     * Starts the encoder and the drain loop. Returns false (and releases
     * resources) on failure; the caller should abort when this is false.
     */
    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return false
        return try {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            scope.launch { drain() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encoder", e)
            started.set(false)
            releaseAll()
            false
        }
    }

    /**
     * Draws one frame onto the encoder input surface. [block] receives a canvas
     * covering the whole recording bounds and must fill it (background +
     * content). Draws must run on the caller's thread; encoding happens
     * asynchronously.
     *
     * Returns false when the encoder is not accepting frames right now (still
     * starting, stopping, or the input is saturated) — the frame is dropped and
     * recording continues.
     */
    fun present(block: (Canvas) -> Unit): Boolean {
        if (!started.get() || stopping.get()) return false
        val surface = inputSurface ?: return false
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (e: Exception) {
            Log.w(TAG, "Encoder input canvas unavailable", e)
            null
        } ?: return false
        return try {
            block(canvas)
            surface.unlockCanvasAndPost(canvas)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Frame drawing failed", e)
            false
        }
    }

    /**
     * Stops recording and finalizes the MP4. Suspends until the encoder is
     * drained (bounded by [FINALIZE_TIMEOUT_MS]). Returns true when a playable
     * file was produced (zero frames → false, caller should discard the file).
     */
    suspend fun stop(): Boolean {
        if (!started.get()) return false
        stopping.set(true)
        runCatching { codec.signalEndOfInputStream() }
            .onFailure { Log.w(TAG, "signalEndOfInputStream failed", it) }
        val ok = withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { drained.await() } ?: false
        scope.cancel()
        return ok
    }

    private suspend fun drain() {
        val info = MediaCodec.BufferInfo()
        var finished = false
        while (!finished) {
            val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (muxerStarted) {
                            muxer.writeSampleData(trackIndex, buffer, info)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        finished = true
                    }
                }
            }
        }
        val ok = runCatching {
            muxer.stop()
            true
        }.getOrDefault(false)
        releaseAll()
        drained.complete(ok)
    }

    private fun releaseAll() {
        runCatching { muxer.stop() }
        runCatching { muxer.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        inputSurface?.let { runCatching { it.release() } }
        inputSurface = null
        runCatching { output.close() }
    }
}
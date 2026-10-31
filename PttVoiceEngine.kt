// SPDX-License-Identifier: GPL-2.0-or-later
// Capture/playback architecture adapted from yoyo-san/bmo (MIT); see notices.
package com.example.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground-screen, half-duplex PTT. Create on main thread while RESUMED.
 * Terminal close on ON_STOP: create another engine when the screen resumes.
 * sendFast must await the existing transport's actual write, not merely enqueue.
 * AAC-LC: 16 kHz mono PCM16, 32 kbit/s, one 64 ms access unit per MeshPacket.
 * 20 KiB is a ceiling, never a batching target. TCP cannot guarantee <400 ms.
 */
class PttVoiceEngine(
    context: Context,
    private val owner: LifecycleOwner,
    private val localId: String,
    private val clusterId: UUID,
    private val sendFast: suspend (MeshPacket) -> Unit,
    private val authorizeVoice: suspend (String, UUID) -> Boolean = { _, _ -> false },
    private val mayPlayVoice: (String, UUID) -> Boolean = { _, _ -> false }
) : Closeable {
    private val app = context.applicationContext
    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + Dispatchers.IO)
    private val closed = AtomicBoolean()
    private val generation = AtomicLong()
    private val captureMutex = Mutex()
    private var captureJob: Job? = null // Main thread only.
    private val speaking = AtomicBoolean()
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change < 0) close()
        }, android.os.Handler(Looper.getMainLooper())).build()
    private val mutableErrors = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)
    val errors = mutableErrors.asSharedFlow()
    private data class Outgoing(val generation: Long, val at: Long, val packet: MeshPacket)
    private data class Incoming(val sender: String, val at: Long, val frame: VoiceFrame)
    private val outgoing = Channel<Outgoing>(2, BufferOverflow.DROP_OLDEST)
    private val incoming = Channel<Incoming>(2, BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { it.frame.audio.fill(0) })
    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) close()
    }

    init {
        mainThread()
        check(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        check(audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Audio focus unavailable"
        }
        owner.lifecycle.addObserver(observer)
        scope.launch {
            for (item in outgoing) {
                if (item.generation != generation.get() || now() - item.at > 120) continue
                try { sendFast(item.packet) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutableErrors.tryEmit(e) }
            }
        }
        scope.launch { playbackLoop() }
    }

    /** Call after RECORD_AUDIO permission is granted, on pointer-down. */
    fun startTalking(targetId: String? = MeshPacket.BROADCAST) {
        mainThread()
        check(!closed.get() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        check(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        require(MeshPacket.isValidTarget(targetId))
        if (captureJob?.isActive == true) return
        val token = generation.incrementAndGet()
        speaking.set(true)
        captureJob = scope.launch {
            try { captureMutex.withLock { capture(token, targetId) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableErrors.tryEmit(e) }
            finally { if (generation.get() == token) speaking.set(false) }
        }
    }

    /** Pointer-up/cancel. This releases the microphone without blocking the UI. */
    fun stopTalking() {
        mainThread()
        generation.incrementAndGet()
        speaking.set(false)
        captureJob?.cancel()
    }

    /** Disable the PTT button while awaiting this before giving the mic to CameraX. */
    suspend fun stopTalkingAndJoin() = withContext(Dispatchers.Main.immediate) {
        stopTalking()
        captureJob?.join()
    }

    /** Safe from the relay callback: copies only this bounded voice payload. */
    fun consume(header: MeshPacket.FrameHeader, payload: ByteBuffer): Boolean {
        if (header.type != PacketType.PTT_VOICE) return false
        if (closed.get() || header.senderId == localId || speaking.get()) return true
        if (header.targetId != null && header.targetId != MeshPacket.BROADCAST && header.targetId != localId) return true
        try {
            val frame = VoiceFrame.decode(payload)
            if (!mayPlayVoice(header.senderId, frame.streamId)) { frame.audio.fill(0); return true }
            if (!incoming.trySend(Incoming(header.senderId, now(), frame)).isSuccess) frame.audio.fill(0)
        } catch (_: IllegalArgumentException) { /* Malformed voice payload: discard. */ }
        return true
    }

    private suspend fun capture(token: Long, targetId: String?) {
        require(targetId != null && MeshPacket.isValidIdentity(targetId)) { "Select one peer for voice consent" }
        val stream = UUID.randomUUID()
        if (!authorizeVoice(targetId, stream)) throw RequestDeclinedException()
        currentCoroutineContext().ensureActive()
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "16 kHz recording unavailable" }
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
        var codec: MediaCodec? = null
        val pcm = ByteArray(640) // 20 ms; AAC naturally emits 1024-sample / 64 ms units.
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED)
            val encoder = MediaCodec.createEncoderByType(MIME).also { codec = it }
            encoder.configure(aacFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            var sequence = 0
            var samples = 0L
            var firstPts: Long? = null
            val info = MediaCodec.BufferInfo()
            while (currentCoroutineContext().isActive && generation.get() == token) {
                val index = encoder.dequeueInputBuffer(0)
                if (index >= 0) {
                    val buffer = checkNotNull(encoder.getInputBuffer(index))
                    buffer.clear()
                    var count: Int
                    do {
                        currentCoroutineContext().ensureActive()
                        count = record.read(pcm, 0, minOf(pcm.size, buffer.remaining()) and -2, AudioRecord.READ_NON_BLOCKING)
                        check(count >= 0 && count % 2 == 0) { "AudioRecord failed: $count" }
                        if (count == 0) delay(2)
                    } while (count == 0)
                    buffer.put(pcm, 0, count)
                    encoder.queueInputBuffer(index, 0, count, samples * 1_000_000 / RATE, 0)
                    samples += count / 2
                }
                while (true) {
                    val output = encoder.dequeueOutputBuffer(info, 0)
                    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    if (output < 0) break
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            require(info.size <= VoiceFrame.MAX_AUDIO)
                            val data = ByteArray(info.size)
                            checkNotNull(encoder.getOutputBuffer(output)).apply {
                                position(info.offset); limit(info.offset + info.size); get(data)
                            }
                            if (firstPts == null) firstPts = info.presentationTimeUs
                            val pts = (info.presentationTimeUs - checkNotNull(firstPts)).coerceAtLeast(0)
                            val encoded = VoiceFrame(stream, sequence++, pts, data).encode()
                            data.fill(0)
                            val packet = MeshPacket(PacketType.PTT_VOICE, localId, clusterId, encoded, targetId)
                            encoded.fill(0)
                            outgoing.trySend(Outgoing(token, now(), packet))
                        }
                    } finally { encoder.releaseOutputBuffer(output, false) }
                }
                delay(2)
            }
            // PTT release discards the encoder tail instead of accumulating stale speech.
        } finally {
            pcm.fill(0)
            runCatching { record.stop() }; record.release()
            codec?.let { runCatching { it.stop() }; it.release() }
        }
    }

    private suspend fun playbackLoop() {
        val browserAudio = BrowserVoicePlayer()
        var decoder: MediaCodec? = null
        var track: AudioTrack? = null
        var speaker: String? = null
        var stream: UUID? = null
        var sequence = -1
        var lastArrival = 0L
        var anchorTime = 0L
        var anchorPts = 0L
        var writtenFrames = 0L
        fun reset() {
            browserAudio.close()
            track?.let { runCatching { it.pause(); it.flush() }; it.release() }; track = null
            decoder?.let { runCatching { it.stop() }; it.release() }; decoder = null
            speaker = null; stream = null; sequence = -1; writtenFrames = 0
        }
        try {
            while (currentCoroutineContext().isActive) {
                val item = withTimeoutOrNull(80) { incoming.receive() }
                if (item == null) {
                    if (speaking.get() || now() - lastArrival > 250) reset()
                    continue
                }
                try {
                    if (speaking.get()) { reset(); continue }
                    if (now() - item.at > 120) continue
                    val f = item.frame
                    if (speaker != null && speaker != item.sender && item.at - lastArrival < 250) continue
                    if (speaker != item.sender || stream != f.streamId || item.at - lastArrival > 250) {
                        reset()
                        speaker = item.sender; stream = f.streamId
                        anchorTime = item.at; anchorPts = f.ptsUs
                    }
                    if (f.sequence <= sequence) continue
                    if (item.at - anchorTime - (f.ptsUs - anchorPts) / 1000 > 150) {
                        // Once a TCP backlog is detected, discard this talk burst until idle.
                        lastArrival = item.at
                        continue
                    }
                    if (sequence >= 0 && f.sequence != sequence + 1) {
                        decoder?.flush()
                        track?.pause(); track?.flush(); track?.play(); writtenFrames = 0
                    }
                    sequence = f.sequence; lastArrival = item.at
                    if (f.containerKind != 0) {
                        decoder?.let { runCatching { it.stop() }; it.release() }; decoder = null
                        track?.release(); track = null
                        browserAudio.play(f.audio) { !speaking.get() && !closed.get() }
                        continue
                    }
                    browserAudio.close()
                    if (decoder == null) {
                        decoder = MediaCodec.createDecoderByType(MIME).also {
                            val format = aacFormat()
                            format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x14, 0x08)))
                            it.configure(format, null, null, 0); it.start()
                        }
                    }
                    val dec = checkNotNull(decoder)
                    val input = dec.dequeueInputBuffer(5_000)
                    if (input < 0) { reset(); continue }
                    checkNotNull(dec.getInputBuffer(input)).apply { clear(); put(f.audio) }
                    dec.queueInputBuffer(input, 0, f.audio.size, f.ptsUs, 0)
                    val info = MediaCodec.BufferInfo()
                    while (true) {
                        val output = dec.dequeueOutputBuffer(info, 5_000)
                        if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val fmt = dec.outputFormat
                            require(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) == RATE &&
                                fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
                            if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                require(fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                            track?.release()
                            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                            check(min > 0)
                            track = AudioTrack.Builder()
                                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(min, 4096)).build()
                                .also { check(it.state == AudioTrack.STATE_INITIALIZED); it.play() }
                            writtenFrames = 0
                            continue
                        }
                        if (output < 0) break
                        try {
                            val sink = checkNotNull(track)
                            // Never let a slow output device accumulate unbounded PCM.
                            val played = sink.playbackHeadPosition.toLong() and 0xffffffffL
                            if (writtenFrames - played > RATE / 8) {
                                sink.pause(); sink.flush(); sink.play(); writtenFrames = 0
                            }
                            val pcm = checkNotNull(dec.getOutputBuffer(output))
                            pcm.position(info.offset); pcm.limit(info.offset + info.size)
                            val count = sink.write(pcm, info.size, AudioTrack.WRITE_NON_BLOCKING)
                            check(count >= 0 && count % 2 == 0) { "AudioTrack failed: $count" }
                            writtenFrames += count / 2 // Any unwritten tail is intentionally dropped.
                        } finally { dec.releaseOutputBuffer(output, false) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { reset(); mutableErrors.tryEmit(e) }
                finally { item.frame.audio.fill(0) }
            }
        } finally { reset() }
    }

    override fun close() {
        mainThread()
        if (!closed.compareAndSet(false, true)) return
        owner.lifecycle.removeObserver(observer)
        audioManager.abandonAudioFocusRequest(focus)
        stopTalking(); root.cancel(); incoming.cancel(); outgoing.cancel()
    }

    companion object {
        private const val RATE = 16_000
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private fun now() = SystemClock.elapsedRealtime()
        private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper()) { "Call on main thread" }
        private fun aacFormat() = MediaFormat.createAudioFormat(MIME, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
        }
    }
}

/** Versioned application payload, inside the existing CRC-protected MeshPacket. Not a new transport. */
internal data class VoiceFrame(val streamId: UUID, val sequence: Int, val ptsUs: Long, val audio: ByteArray, val containerKind: Int = 0) {
    fun encode(): ByteArray {
        require(containerKind == 0) // Native encoder remains PTT1 AAC; browsers use PTT2.
        require(sequence >= 0 && ptsUs in 0..86_400_000_000L && audio.size in 1..MAX_AUDIO)
        return ByteBuffer.allocate(HEADER + audio.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).putLong(streamId.mostSignificantBits).putLong(streamId.leastSignificantBits)
            .putInt(sequence).putLong(ptsUs).put(audio).array()
    }
    companion object {
        const val MAX_PAYLOAD = 20 * 1024
        private const val HEADER = 32
        const val MAX_AUDIO = MAX_PAYLOAD - HEADER
        private const val MAGIC = 0x50545431 // PTT1: fixed AAC-LC/16000/mono, ASC 0x1408.
        fun decode(source: ByteBuffer): VoiceFrame {
            val b = source.duplicate().order(ByteOrder.BIG_ENDIAN)
            require(b.remaining() in (HEADER + 1)..MAX_PAYLOAD)
            val magic = b.int
            require(magic == MAGIC || magic == BrowserVoiceWire.MAGIC)
            val id = UUID(b.long, b.long)
            val sequence = b.int
            val pts = b.long
            require(sequence >= 0 && pts in 0..86_400_000_000L) // Maximum 24-hour burst.
            val kind = if (magic == BrowserVoiceWire.MAGIC) b.get().toInt().also { require(it in 1..2 && b.hasRemaining()) } else 0
            return VoiceFrame(id, sequence, pts, ByteArray(b.remaining()).also { b.get(it) }, kind)
        }
    }
}

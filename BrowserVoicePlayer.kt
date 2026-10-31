// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import android.media.*
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.Closeable

/** Android endpoint decoder for complete MediaRecorder containers, using platform demux/codec APIs. */
internal class BrowserVoicePlayer : Closeable {
    private var track: AudioTrack? = null
    private var rate = 0
    private var channels = 0
    suspend fun play(bytes: ByteArray, mayPlay: () -> Boolean) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val memory = object : MediaDataSource() {
            override fun getSize() = bytes.size.toLong()
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position < 0 || position >= bytes.size) return -1
                val count = minOf(size, bytes.size - position.toInt())
                bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                return count
            }
            override fun close() = Unit
        }
        try {
            extractor.setDataSource(memory)
            val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val format = extractor.getTrackFormat(index)
            val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
            require(mime in listOf(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_OPUS))
            extractor.selectTrack(index)
            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(format, null, null, 0); decoder.start()
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 400
            while (SystemClock.elapsedRealtime() < deadline && mayPlay()) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val input = decoder.dequeueInputBuffer(0)
                    if (input >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(input)).apply { clear() }
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            decoder.queueInputBuffer(input, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 2_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val decoded = decoder.outputFormat
                    val sampleRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val count = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    require(sampleRate in 8_000..48_000 && count in 1..2)
                    if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) require(decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                    if (rate != sampleRate || channels != count || track == null) {
                        close(); rate = sampleRate; channels = count
                        val mask = if (count == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                        val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                        require(minimum > 0)
                        track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, rate * count / 5)).build().also {
                                check(it.state == AudioTrack.STATE_INITIALIZED); it.play()
                            }
                    }
                } else if (output >= 0) {
                    try {
                        val buffer = checkNotNull(decoder.getOutputBuffer(output)).apply { position(info.offset); limit(info.offset + info.size) }
                        while (buffer.hasRemaining() && SystemClock.elapsedRealtime() < deadline && mayPlay()) {
                            currentCoroutineContext().ensureActive()
                            val written = checkNotNull(track).write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                            check(written >= 0)
                            if (written == 0) delay(2)
                        }
                    } finally { decoder.releaseOutputBuffer(output, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release(); memory.close()
        }
    }
    override fun close() {
        track?.let { runCatching { it.pause(); it.flush() }; it.release() }
        track = null
    }
}

package ir.soundrecorder.pro.recorder

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.AudioPlaybackCaptureConfiguration
import android.os.SystemClock
import ir.soundrecorder.pro.storage.AudioStorage
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Captures only playback usages/apps that Android and the source app permit to be captured. */
class InternalAudioRecorder(private val context: Context) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null

    @Synchronized fun start(projection: MediaProjection) {
        if (running.get()) return
        val sampleRate = 48000
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0) { "AudioRecord buffer configuration is unsupported" }

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer * 2, 16 * 1024))
            .setAudioPlaybackCaptureConfig(config)
            .build()

        val encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioRecord = record
        codec = enc
        running.set(true)
        record.startRecording()
        enc.start()
        worker = Thread({ encodeLoop(record, enc, sampleRate) }, "InternalAudioEncoder").apply { start() }
    }

    private fun encodeLoop(record: AudioRecord, enc: MediaCodec, sampleRate: Int) {
        val info = MediaCodec.BufferInfo()
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var outputFormat: MediaFormat? = null
        var segmentStartedAt = SystemClock.elapsedRealtime()
        val pcm = ByteArray(8192)
        var ptsUs = 0L
        try {
            while (running.get()) {
                val inputIndex = enc.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val input = enc.getInputBuffer(inputIndex) ?: continue
                    input.clear()
                    val count = record.read(pcm, 0, minOf(pcm.size, input.remaining()))
                    if (count > 0) {
                        input.put(pcm, 0, count)
                        val frames = count / 4 // 16-bit stereo
                        val durationUs = frames * 1_000_000L / sampleRate
                        enc.queueInputBuffer(inputIndex, 0, count, ptsUs, 0)
                        ptsUs += durationUs
                    } else {
                        enc.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0)
                    }
                }

                var outputIndex = enc.dequeueOutputBuffer(info, 10000)
                while (outputIndex >= 0) {
                    val out = enc.getOutputBuffer(outputIndex)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && out != null && outputFormat != null) {
                        if (muxer == null || SystemClock.elapsedRealtime() - segmentStartedAt >= 5 * 60 * 1000L) {
                            stopMuxer(muxer)
                            val file = AudioStorage.newM4a(context, "internal")
                            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                            trackIndex = muxer!!.addTrack(outputFormat!!)
                            muxer!!.start()
                            segmentStartedAt = SystemClock.elapsedRealtime()
                        }
                        val sample = out.duplicate()
                        sample.position(info.offset)
                        sample.limit(info.offset + info.size)
                        muxer?.writeSampleData(trackIndex, sample, info)
                    }
                    enc.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    outputIndex = enc.dequeueOutputBuffer(info, 0)
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = enc.outputFormat
                }
            }
        } catch (_: Throwable) {
            // The service reports status; a failed internal capture must not stop microphone recording.
        } finally {
            stopMuxer(muxer)
            runCatching { record.stop() }
            runCatching { enc.stop() }
            runCatching { enc.release() }
            runCatching { record.release() }
        }
    }

    private fun stopMuxer(muxer: MediaMuxer?) {
        if (muxer != null) {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    @Synchronized fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { audioRecord?.stop() }
        worker?.join(2500)
        worker = null
        audioRecord = null
        codec = null
    }
}

package ir.soundrecorder.pro.recorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import ir.soundrecorder.pro.storage.AudioStorage
import java.io.File

class MicrophoneRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    @Synchronized fun startSegment() {
        check(recorder == null)
        val file = AudioStorage.temporary(context, "environment", "m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(96000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            currentFile = file
        } catch (t: Throwable) {
            runCatching { r.release() }
            file.delete()
            throw t
        }
    }

    @Synchronized fun stop(): String? {
        val r = recorder ?: return null
        val file = checkNotNull(currentFile)
        recorder = null
        currentFile = null
        try { r.stop() } catch (e: RuntimeException) {
            file.delete()
            throw IllegalStateException("ضبط میکروفون نهایی نشد؛ ممکن است خیلی کوتاه یا مسدود بوده باشد", e)
        } finally { runCatching { r.release() } }
        return AudioStorage.finish(context, file)
    }
}

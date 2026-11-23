package ir.soundrecorder.pro.recorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import ir.soundrecorder.pro.storage.AudioStorage
import java.io.File

/** Records the acoustic environment through the device microphone. */
class MicrophoneRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    @Synchronized fun startSegment(): File {
        stopSegment()
        val file = AudioStorage.newM4a(context, "environment")
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
            return file
        } catch (t: Throwable) {
            runCatching { r.reset() }
            runCatching { r.release() }
            file.delete()
            throw t
        }
    }

    @Synchronized fun stopSegment() {
        val r = recorder ?: return
        runCatching { r.stop() }
        runCatching { r.reset() }
        runCatching { r.release() }
        recorder = null
        currentFile = null
    }

    fun stop() = stopSegment()
}

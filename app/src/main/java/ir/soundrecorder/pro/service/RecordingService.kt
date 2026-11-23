package ir.soundrecorder.pro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Activity
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import ir.soundrecorder.pro.MainActivity
import ir.soundrecorder.pro.recorder.InternalAudioRecorder
import ir.soundrecorder.pro.recorder.MicrophoneRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingState(val phase: String = "idle", val message: String = "برای شروع، نوع ضبط را انتخاب کنید.", val revision: Long = 0)

class RecordingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var microphone: MicrophoneRecorder
    private var internal: InternalAudioRecorder? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var foreground = false
    private var stopping = false
    private var running = false
    private var micActive = false
    private val warnings = mutableListOf<String>()
    private fun update(phase: String, message: String) { mutableState.value = RecordingState(phase, message, mutableState.value.revision + 1) }
    private fun report(message: String?) {
        if (message != null && message !in warnings) warnings += message
        if (running && !stopping) update("running", message ?: "یک بخش ضبط ذخیره شد؛ ضبط ادامه دارد.")
    }
    private val rotateMic = object : Runnable {
        override fun run() {
            if (!running || stopping || !micActive) return
            try {
                report(microphone.stop())
                microphone.startSegment()
                handler.postDelayed(this, SEGMENT_MS)
            } catch (e: Exception) { finish("خطای ضبط میکروفون: ${e.localizedMessage}") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        microphone = MicrophoneRecorder(this)
        checkNotNull(getSystemService(NotificationManager::class.java)).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { finish(); return START_NOT_STICKY }
        if (running || stopping) return START_NOT_STICKY
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        warnings.clear()
        update("starting", "در حال شروع ضبط…")
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_MIC
        val wantsMic = mode != MODE_INTERNAL
        val wantsInternal = mode != MODE_MIC
        try {
            val types = (if (wantsMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
                (if (wantsInternal) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            startForeground(NOTIFICATION_ID, notification(), types)
            foreground = true
            if (wantsMic) { microphone.startSegment(); micActive = true }
            if (wantsInternal) {
                val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else legacyData(intent)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                check(code == Activity.RESULT_OK && data != null) { "اجازه ضبط صدای داخلی داده نشده است" }
                val p = checkNotNull(checkNotNull(getSystemService(MediaProjectionManager::class.java)).getMediaProjection(code, data))
                projection = p
                val cb = object : MediaProjection.Callback() { override fun onStop() { if (!stopping) finish("مجوز ضبط صدای داخلی پایان یافت.") } }
                projectionCallback = cb
                p.registerCallback(cb, handler)
                val capture = InternalAudioRecorder(this, { msg -> handler.post { report(msg) } }, { error ->
                    handler.post { if (!stopping && running) finish("خطای صدای داخلی: ${error.localizedMessage}") else report("خطای صدای داخلی: ${error.localizedMessage}") }
                })
                internal = capture
                capture.start(p)
            }
            running = true
            if (micActive) handler.postDelayed(rotateMic, SEGMENT_MS)
            update("running", "ضبط فعال است. برای پایان، توقف را بزنید.")
        } catch (t: Throwable) {
            Log.e(TAG, "Start failed", t)
            finish("شروع ضبط ناموفق بود: ${t.localizedMessage ?: t.javaClass.simpleName}")
        }
        return START_NOT_STICKY
    }

    private fun finish(reason: String? = null) {
        if (stopping) return
        stopping = true
        running = false
        handler.removeCallbacks(rotateMic)
        update("stopping", "در حال نهایی‌سازی و ذخیره فایل‌ها…")
        val capture = internal
        internal = null
        val p = projection
        projection = null
        projectionCallback?.let { cb -> runCatching { p?.unregisterCallback(cb) } }
        projectionCallback = null
        val hadMic = micActive
        micActive = false
        Thread({
            val errors = mutableListOf<String>()
            if (hadMic) try { microphone.stop()?.let { errors += it } } catch (e: Exception) { errors += (e.localizedMessage ?: "خطای ذخیره میکروفون") }
            try { capture?.stop() } catch (e: Exception) { errors += (e.localizedMessage ?: "خطای توقف صدای داخلی") }
            runCatching { p?.stop() }
            handler.post {
                val notes = (listOfNotNull(reason) + warnings + errors).distinct()
                update("idle", if (notes.isEmpty()) "ضبط پایان یافت؛ فهرست فایل‌ها به‌روز شد." else notes.joinToString(" | "))
                if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
                stopSelf()
            }
        }, "FinalizeRecording").start()
    }

    override fun onDestroy() { if (!stopping && (running || foreground)) finish("سرویس متوقف شد."); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    @Suppress("DEPRECATION") private fun legacyData(intent: Intent): Intent? = intent.getParcelableExtra(EXTRA_DATA)
    private fun notification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stop = PendingIntent.getService(this, 10, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), flags)
        val open = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java), flags)
        return Notification.Builder(this, CHANNEL_ID).setContentTitle("Sound Recorder Pro")
            .setContentText("ضبط صدا فعال است؛ برای توقف از این اعلان استفاده کنید")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "توقف ضبط", stop).build()).build()
    }
    companion object {
        private val mutableState = MutableStateFlow(RecordingState())
        val state = mutableState.asStateFlow()
        const val ACTION_START = "ir.soundrecorder.pro.START"
        const val ACTION_STOP = "ir.soundrecorder.pro.STOP"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_DATA = "projection_data"
        const val EXTRA_MODE = "recording_mode"
        const val MODE_MIC = "mic"
        const val MODE_INTERNAL = "internal"
        const val MODE_BOTH = "both"
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "sound_recording"
        private const val NOTIFICATION_ID = 501
        private const val SEGMENT_MS = 5 * 60 * 1000L
    }
}

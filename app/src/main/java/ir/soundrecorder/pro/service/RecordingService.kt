package ir.soundrecorder.pro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import ir.soundrecorder.pro.R
import ir.soundrecorder.pro.recorder.InternalAudioRecorder
import ir.soundrecorder.pro.recorder.MicrophoneRecorder

class RecordingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var microphone: MicrophoneRecorder
    private lateinit var internal: InternalAudioRecorder
    private var projection: MediaProjection? = null
    private var isRunning = false
    private val rotateMic = object : Runnable {
        override fun run() {
            if (!isRunning) return
            try { microphone.startSegment() } catch (t: Throwable) { Log.e(TAG, "Mic segment restart failed", t) }
            handler.postDelayed(this, SEGMENT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        microphone = MicrophoneRecorder(this)
        internal = InternalAudioRecorder(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) return START_STICKY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                   else legacyProjectionData(intent)
        if (data == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), types)
            else startForeground(NOTIFICATION_ID, notification())
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopRecording(); stopSelf() }
            }, handler)
            microphone.startSegment()
            handler.postDelayed(rotateMic, SEGMENT_MS)
            projection?.let { internal.start(it) }
            isRunning = true
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to start recording", t)
            stopRecording()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stopRecording() {
        if (!isRunning && projection == null) return
        isRunning = false
        handler.removeCallbacks(rotateMic)
        runCatching { microphone.stop() }
        runCatching { internal.stop() }
        val oldProjection = projection
        projection = null
        runCatching { oldProjection?.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun legacyProjectionData(intent: Intent?): Intent? = intent?.getParcelableExtra(EXTRA_DATA)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(this, 10, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("Sound Recorder Pro")
            .setContentText("ضبط صدا فعال است؛ برای توقف از این اعلان استفاده کنید")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "توقف ضبط", pending).build())
            .build()
    }

    companion object {
        const val ACTION_START = "ir.soundrecorder.pro.START"
        const val ACTION_STOP = "ir.soundrecorder.pro.STOP"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_DATA = "projection_data"
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "sound_recording"
        private const val NOTIFICATION_ID = 501
        private const val SEGMENT_MS = 5 * 60 * 1000L
    }
}

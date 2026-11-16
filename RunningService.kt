package com.quangdung.trolychaybo

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.*

data class WorkoutStep(val name: String, val voice: String, val durationSec: Int, val type: String)

class RunningService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var timer: CountDownTimer? = null

    private var playlist = mutableListOf<WorkoutStep>()
    private var currentStepIndex = 0
    private var timeLeftSec = 0
    private var isRunning = false
    private var lastAnnounced5Sec = false

    companion object {
        const val CHANNEL_ID = "TroLyChayBoChannel"
        const val NOTIF_ID = 1001
        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESET = "ACTION_RESET"
        const val ACTION_TEST_VOICE = "ACTION_TEST_VOICE"
        const val ACTION_LOAD_PLAN = "ACTION_LOAD_PLAN"
        const val ACTION_JUMP_STEP = "ACTION_JUMP_STEP"
        const val BROADCAST_UPDATE = "com.quangdung.trolychaybo.UPDATE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TroLyChayBo::WorkoutWakeLock")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("vi", "VN"))
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setSpeechRate(0.98f)
        }
    }

    private fun speakText(text: String) {
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST_VOICE -> {
                speakText("Xin chào chủ nhân, hệ thống giọng nói tiếng Việt chạy ngầm trên Samsung Galaxy A17 đã sẵn sàng.")
            }
            ACTION_LOAD_PLAN -> {
                val names = intent.getStringArrayListExtra("names") ?: arrayListOf()
                val voices = intent.getStringArrayListExtra("voices") ?: arrayListOf()
                val durations = intent.getIntegerArrayListExtra("durations") ?: arrayListOf()
                val types = intent.getStringArrayListExtra("types") ?: arrayListOf()

                stopWorkoutTimer()
                playlist.clear()
                for (i in names.indices) {
                    playlist.add(WorkoutStep(names[i], voices[i], durations[i], types[i]))
                }
                currentStepIndex = 0
                timeLeftSec = if (playlist.isNotEmpty()) playlist[0].durationSec else 0
                sendStateBroadcast()
            }
            ACTION_START -> {
                if (!isRunning && playlist.isNotEmpty()) {
                    if (currentStepIndex >= playlist.size) {
                        currentStepIndex = 0
                        timeLeftSec = playlist[0].durationSec
                    }
                    startForeground(NOTIF_ID, buildNotification("Đang tập luyện", playlist[currentStepIndex].name))
                    if (wakeLock?.isHeld == false) {
                        wakeLock?.acquire(4 * 60 * 60 * 1000L) // Tối đa 4 tiếng
                    }
                    speakText(playlist[currentStepIndex].voice)
                    startWorkoutTimer()
                }
            }
            ACTION_PAUSE -> {
                stopWorkoutTimer()
                if (wakeLock?.isHeld == true) wakeLock?.release()
                updateNotification("Đã tạm dừng", if (currentStepIndex < playlist.size) playlist[currentStepIndex].name else "")
                sendStateBroadcast()
            }
            ACTION_RESET -> {
                stopWorkoutTimer()
                if (wakeLock?.isHeld == true) wakeLock?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                currentStepIndex = 0
                timeLeftSec = if (playlist.isNotEmpty()) playlist[0].durationSec else 0
                sendStateBroadcast()
            }
            ACTION_JUMP_STEP -> {
                val idx = intent.getIntExtra("stepIndex", 0)
                if (idx in playlist.indices) {
                    stopWorkoutTimer()
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                    currentStepIndex = idx
                    timeLeftSec = playlist[idx].durationSec
                    sendStateBroadcast()
                }
            }
        }
        return START_STICKY
    }

    private fun startWorkoutTimer() {
        isRunning = true
        lastAnnounced5Sec = false
        val endTimeMillis = SystemClock.elapsedRealtime() + timeLeftSec * 1000L

        timer = object : CountDownTimer(timeLeftSec * 1000L, 250L) {
            override fun onTick(millisUntilFinished: Long) {
                val remaining = ((endTimeMillis - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)
                if (remaining != timeLeftSec) {
                    timeLeftSec = remaining
                    if (timeLeftSec == 5 && !lastAnnounced5Sec) {
                        lastAnnounced5Sec = true
                        speakText("Còn 5 giây, chuẩn bị chuyển")
                    }
                    updateNotification(
                        "${formatTime(timeLeftSec)} • Chặng ${currentStepIndex + 1}/${playlist.size}",
                        playlist[currentStepIndex].name
                    )
                    sendStateBroadcast()
                }
            }

            override fun onFinish() {
                timeLeftSec = 0
                currentStepIndex++
                if (currentStepIndex < playlist.size) {
                    timeLeftSec = playlist[currentStepIndex].durationSec
                    speakText(playlist[currentStepIndex].voice)
                    startWorkoutTimer()
                } else {
                    isRunning = false
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                    speakText("Chúc mừng chủ nhân đã hoàn thành xuất sắc buổi tập hôm nay!")
                    updateNotification("Hoàn thành!", "Đã xong toàn bộ buổi tập")
                    sendStateBroadcast()
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            }
        }.start()
        sendStateBroadcast()
    }

    private fun stopWorkoutTimer() {
        timer?.cancel()
        timer = null
        isRunning = false
    }

    private fun sendStateBroadcast() {
        val intent = Intent(BROADCAST_UPDATE).apply {
            setPackage(packageName)
            putExtra("isRunning", isRunning)
            putExtra("currentStep", currentStepIndex)
            putExtra("timeLeft", timeLeftSec)
        }
        sendBroadcast(intent)
    }

    private fun formatTime(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Trợ Lý Chạy Bộ Chạy Ngầm",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(title, content))
    }

    override fun onDestroy() {
        stopWorkoutTimer()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

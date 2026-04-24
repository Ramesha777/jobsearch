package com.wordwatcher

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import com.wordwatcher.data.AppDatabase
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class WatcherService : Service() {

    private val RUNNING_CHANNEL_ID = "WordWatcherRunning"
    private val ALERT_CHANNEL_ID = "WordWatcherAlertsV4"
    private val RUNNING_NOTIF_ID = 1
    private val TARGET_SECOND_MS = 58000L

    companion object {
        const val ACTION_START_MONITORING = "com.wordwatcher.action.START_MONITORING"
        const val ACTION_STOP_SOUND = "com.wordwatcher.action.STOP_SOUND"
        const val ACTION_TEST_ALERT = "com.wordwatcher.action.TEST_ALERT"
        const val ACTION_SCHEDULE_CHECK = "com.wordwatcher.action.SCHEDULE_CHECK"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase
    private var webView: WebView? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(this)
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WordWatcher:HighPriorityLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Essential: Keep the service in foreground immediately
        startForeground(RUNNING_NOTIF_ID, buildForegroundNotification("Active and Monitoring..."))

        when (intent?.action) {
            ACTION_STOP_SOUND -> stopAlertSound()
            ACTION_TEST_ALERT -> triggerAlert("TEST", "https://example.com")
            ACTION_SCHEDULE_CHECK -> performCheckAndReschedule()
            else -> performCheckAndReschedule() // Initial start
        }

        return START_STICKY
    }

    private fun performCheckAndReschedule() {
        wakeLock?.acquire(30000L) // Hold lock for 30s during check
        
        serviceScope.launch {
            checkAllItems()
            
            val timeLabel = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            updateForegroundNotification("Last reload: $timeLabel")
            
            scheduleNextAlarm()
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    private fun scheduleNextAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        val currentMillisInMinute = now % 60000
        
        var targetMillis = TARGET_SECOND_MS - currentMillisInMinute
        if (targetMillis <= 1000) targetMillis += 60000 // If too close, wait for next minute

        val triggerAt = now + targetMillis
        
        val intent = Intent(this, WatcherService::class.java).apply { action = ACTION_SCHEDULE_CHECK }
        val pendingIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        
        Log.d("WatcherService", "Scheduled next check in ${targetMillis/1000}s")
    }

    private suspend fun checkAllItems() {
        val items = db.watchItemDao().getAll()
        for (item in items) {
            try {
                if (!db.watchItemDao().getAll().any { it.id == item.id }) continue

                val html = fetchUrlContent(item.url)
                var found = html.contains(item.keyword, ignoreCase = true)

                if (!found) found = webViewFindKeyword(item.url, item.keyword)

                if (found && db.watchItemDao().getAll().any { it.id == item.id }) {
                    triggerAlert(item.keyword, item.url)
                }
            } catch (e: Exception) {
                Log.e("WatcherService", "Error: ${e.message}")
            }
        }
    }

    private fun fetchUrlContent(urlString: String): String {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                setRequestProperty("Accept-Encoding", "gzip")
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val stream = if (connection.contentEncoding?.lowercase() == "gzip") GZIPInputStream(connection.inputStream) else connection.inputStream
                stream.bufferedReader().use { it.readText() }
            } else ""
        } catch (e: Exception) { "" } finally { connection?.disconnect() }
    }

    private suspend fun webViewFindKeyword(url: String, keyword: String): Boolean = withContext(Dispatchers.Main) {
        suspendCoroutine { cont ->
            var resumed = false
            val wv = (webView ?: WebView(this@WatcherService).also {
                it.settings.javaScriptEnabled = true
                it.settings.domStorageEnabled = true
                webView = it
            })

            val timeoutHandler = Handler(Looper.getMainLooper())
            val timeoutTask = Runnable { if (!resumed) { resumed = true; cont.resume(false) } }
            timeoutHandler.postDelayed(timeoutTask, 25000)

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    view.evaluateJavascript("(function(){ return document.body.innerText || ''; })()") { text ->
                        if (!resumed) {
                            resumed = true
                            timeoutHandler.removeCallbacks(timeoutTask)
                            val cleanText = text?.trim()?.removePrefix("\"")?.removeSuffix("\"") ?: ""
                            cont.resume(cleanText.contains(keyword, ignoreCase = true))
                        }
                    }
                }
            }
            wv.loadUrl(url)
        }
    }

    private fun triggerAlert(keyword: String, url: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            stopAlertSound()
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, soundUri)
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                isLooping = true
                prepare()
                start()
            }
            Handler(Looper.getMainLooper()).postDelayed({ stopAlertSound() }, 18000)
        } catch (e: Exception) { Log.e("WatcherService", "Sound error: ${e.message}") }

        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val pattern = LongArray(24) { if (it % 2 == 0) 1000L else 500L }
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))

        val stopIntent = Intent(this, WatcherService::class.java).apply { action = ACTION_STOP_SOUND }
        val stopPending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        
        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Keyword Found!")
            .setContentText("Found at ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .addAction(android.R.drawable.ic_lock_silent_mode, "Stop Sound", stopPending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun updateForegroundNotification(status: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(RUNNING_NOTIF_ID, buildForegroundNotification(status))
    }

    private fun buildForegroundNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, RUNNING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Word Watcher")
            .setContentText(status)
            .setContentIntent(pending)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(RUNNING_CHANNEL_ID, "Service Status", NotificationManager.IMPORTANCE_LOW))
            val alertChannel = NotificationChannel(ALERT_CHANNEL_ID, "Keyword Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setSound(null, null) 
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun stopAlertSound() {
        try {
            mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() }
        } catch (e: Exception) {}
        mediaPlayer = null
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Restart the service if the user swipes it away
        val restartServiceIntent = Intent(applicationContext, this.javaClass)
        restartServiceIntent.setPackage(packageName)
        val restartServicePendingIntent = PendingIntent.getService(applicationContext, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopAlertSound()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        Handler(Looper.getMainLooper()).post { webView?.destroy(); webView = null }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

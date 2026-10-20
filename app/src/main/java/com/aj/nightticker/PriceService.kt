package com.aj.nightticker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 화면이 꺼져도 계속 도는 서비스.
 * 주기마다 Finnhub에서 가격을 받아 TTS로 읽어준다.
 */
class PriceService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_SPEAK_NOW = "speak_now"
        const val BROADCAST = "com.aj.nightticker.UPDATE"
        const val CHANNEL = "nightticker"
        var running = false
            private set
        /** 마지막 조회 결과 (화면 표시용) */
        val lastQuotes = LinkedHashMap<String, Quote>()
        var lastLog = ""
    }

    data class Quote(val code: String, val name: String, val price: Double, val changePct: Double)

    private lateinit var prefs: Prefs
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val spoken = HashMap<String, Double>()   // 마지막으로 읽어준 가격
    private var wakeLock: PowerManager.WakeLock? = null

    private val ticker = object : Runnable {
        override fun run() {
            tick(force = false)
            handler.postDelayed(this, prefs.intervalMin * 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                tts?.setSpeechRate(prefs.speechRate)
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                ttsReady = true
            } else log("음성 엔진을 켜지 못했습니다")
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nightticker:poll")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
            ACTION_SPEAK_NOW -> { startForeground(1, notification("확인 중…")); tick(force = true); if (!running) handler.postDelayed({ if (!running) stopAll() }, 20_000) }
            else -> startWatching()
        }
        return START_STICKY
    }

    private fun startWatching() {
        startForeground(1, notification("감시 중 · ${prefs.intervalMin}분마다"))
        if (running) return
        running = true
        spoken.clear()
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
        speakWhenReady("밤장 감시를 시작합니다.")
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 2500)   // TTS 준비 시간
        log("시작 · ${prefs.intervalMin}분마다 · " + (if (prefs.onlyOnChange) "${prefs.thresholdPct}% 변동 시" else "매번") + " 읽기")
        broadcast()
    }

    private fun stopAll() {
        running = false
        handler.removeCallbacks(ticker)
        tts?.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        log("멈춤")
        broadcast()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun tick(force: Boolean) {
        if (!force && prefs.marketOnly && !marketOpen()) {
            updateNotification("장 마감 · 대기 중")
            return
        }
        updateNotification("확인 중…")
        worker.execute {
            val quotes = fetchAll()
            val parts = ArrayList<String>()
            for (q in quotes) {
                val prev = spoken[q.code]
                val moved = prev == null || abs((q.price - prev) / prev * 100) >= prefs.thresholdPct
                if (force || !prefs.onlyOnChange || moved) {
                    parts.add(sentence(q)); spoken[q.code] = q.price
                }
            }
            handler.post {
                if (quotes.isNotEmpty()) {
                    val summary = quotes.joinToString("  ") { q ->
                        val sign = if (q.changePct >= 0) "+" else ""
                        "${q.name} ${String.format(Locale.US, "%.2f", q.price)}(${sign}${String.format(Locale.US, "%.1f", q.changePct)}%)"
                    }
                    log((if (parts.isEmpty()) "확인 · 변동 없음 · " else "확인 · ") + summary)
                }
                if (parts.isNotEmpty()) speakWhenReady(parts.joinToString(" "))
                updateNotification(if (running) "감시 중 · ${prefs.intervalMin}분마다" else "확인 완료")
                broadcast()
            }
        }
    }

    private fun fetchAll(): List<Quote> {
        val out = ArrayList<Quote>()
        for (s in prefs.symbolList()) {
            try {
                val url = URL("https://finnhub.io/api/v1/quote?symbol=${URLEncoder.encode(s.code, "UTF-8")}&token=${URLEncoder.encode(prefs.apiKey, "UTF-8")}")
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                val body = c.inputStream.bufferedReader().readText()
                val j = JSONObject(body)
                val price = j.optDouble("c", 0.0)
                if (price <= 0.0) throw Exception("데이터 없음")
                val q = Quote(s.code, s.name, price, j.optDouble("dp", 0.0))
                synchronized(lastQuotes) { lastQuotes[s.code] = q }
                out.add(q)
            } catch (e: Exception) {
                log("${s.code} 조회 실패: ${e.message}")
            }
        }
        return out
    }

    private fun sentence(q: Quote): String {
        val price = if (q.price >= 100) String.format(Locale.US, "%.0f", q.price) else String.format(Locale.US, "%.1f", q.price)
        val dir = when { q.changePct > 0 -> "상승"; q.changePct < 0 -> "하락"; else -> "보합" }
        val pct = String.format(Locale.US, "%.1f", abs(q.changePct))
        return "${q.name} ${price}달러, ${pct}퍼센트 $dir."
    }

    /** 뉴욕 시간 평일 09:30~16:00 */
    private fun marketOpen(): Boolean {
        val ny = ZonedDateTime.now(ZoneId.of("America/New_York"))
        if (ny.dayOfWeek == DayOfWeek.SATURDAY || ny.dayOfWeek == DayOfWeek.SUNDAY) return false
        val t = ny.toLocalTime()
        return !t.isBefore(LocalTime.of(9, 30)) && t.isBefore(LocalTime.of(16, 0))
    }

    private fun speakWhenReady(text: String, tries: Int = 0) {
        if (ttsReady) {
            tts?.setSpeechRate(prefs.speechRate)
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "nt")
            log("🔊 $text")
        } else if (tries < 10) handler.postDelayed({ speakWhenReady(text, tries + 1) }, 500)
    }

    private fun log(msg: String) {
        val t = String.format(Locale.KOREA, "%tR", System.currentTimeMillis())
        lastLog = "$t  $msg\n$lastLog".take(8000)
        broadcast()
    }

    private fun broadcast() = sendBroadcast(Intent(BROADCAST).setPackage(packageName))

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "밤장 읽어주기", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PriceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("밤장 읽어주기")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "멈춤", stop)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        tts?.shutdown()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

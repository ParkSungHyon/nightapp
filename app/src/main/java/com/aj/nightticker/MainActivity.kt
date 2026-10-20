package com.aj.nightticker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var testTts: TextToSpeech? = null

    private val intervals = listOf(1, 3, 5, 10, 15, 30)
    private val thresholds = listOf(0.5f, 1f, 2f, 3f, 5f)
    private val rates = listOf(0.8f to "느리게", 0.95f to "보통", 1.15f to "빠르게")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        // 설정 채우기
        findViewById<EditText>(R.id.apikey).setText(prefs.apiKey)
        findViewById<EditText>(R.id.symbols).setText(prefs.symbols)
        spinner(R.id.interval, intervals.map { "${it}분" }, intervals.indexOf(prefs.intervalMin).coerceAtLeast(0))
        spinner(R.id.mode, listOf("변동 있을 때만 읽기", "매번 읽기 (주기마다)"), if (prefs.onlyOnChange) 0 else 1)
        spinner(R.id.threshold, thresholds.map { "${it}%".replace(".0%", "%") }, thresholds.indexOf(prefs.thresholdPct).coerceAtLeast(0))
        spinner(R.id.rate, rates.map { it.second }, rates.indexOfFirst { it.first == prefs.speechRate }.coerceAtLeast(0))
        findViewById<CheckBox>(R.id.marketOnly).isChecked = prefs.marketOnly

        findViewById<Button>(R.id.toggle).setOnClickListener {
            saveSettings()
            if (PriceService.running) send(PriceService.ACTION_STOP) else { askBattery(); send(PriceService.ACTION_START) }
        }
        findViewById<Button>(R.id.speakNow).setOnClickListener { saveSettings(); send(PriceService.ACTION_SPEAK_NOW) }
        findViewById<Button>(R.id.testVoice).setOnClickListener { testVoice() }
        findViewById<Button>(R.id.settingsToggle).setOnClickListener {
            val box = findViewById<LinearLayout>(R.id.settingsBox)
            box.visibility = if (box.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        refresh()
    }

    private fun spinner(id: Int, items: List<String>, selected: Int) {
        val s = findViewById<Spinner>(id)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        s.setSelection(selected)
    }

    private fun saveSettings() {
        prefs.apiKey = findViewById<EditText>(R.id.apikey).text.toString()
        prefs.symbols = findViewById<EditText>(R.id.symbols).text.toString()
        prefs.intervalMin = intervals[findViewById<Spinner>(R.id.interval).selectedItemPosition]
        prefs.onlyOnChange = findViewById<Spinner>(R.id.mode).selectedItemPosition == 0
        prefs.thresholdPct = thresholds[findViewById<Spinner>(R.id.threshold).selectedItemPosition]
        prefs.speechRate = rates[findViewById<Spinner>(R.id.rate).selectedItemPosition].first
        prefs.marketOnly = findViewById<CheckBox>(R.id.marketOnly).isChecked
    }

    private fun send(action: String) {
        val i = Intent(this, PriceService::class.java).setAction(action)
        if (action == PriceService.ACTION_STOP) startService(i) else ContextCompat.startForegroundService(this, i)
        findViewById<Button>(R.id.toggle).postDelayed({ refresh() }, 400)
    }

    /** 삼성 등에서 백그라운드 앱을 죽이지 않도록 배터리 최적화 제외 요청 (한 번만 뜸) */
    private fun askBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
    }

    private fun testVoice() {
        saveSettings()
        if (testTts == null) {
            testTts = TextToSpeech(this) { st ->
                if (st == TextToSpeech.SUCCESS) { testTts?.language = Locale.KOREAN; testTts?.setSpeechRate(prefs.speechRate)
                    testTts?.speak("테슬라 432달러, 0.8퍼센트 상승. 팔란티어 178달러, 1.2퍼센트 하락.", TextToSpeech.QUEUE_FLUSH, null, "t") }
            }
        } else {
            testTts?.setSpeechRate(prefs.speechRate)
            testTts?.speak("테슬라 432달러, 0.8퍼센트 상승. 팔란티어 178달러, 1.2퍼센트 하락.", TextToSpeech.QUEUE_FLUSH, null, "t")
        }
    }

    private fun refresh() {
        val running = PriceService.running
        findViewById<Button>(R.id.toggle).text = if (running) "■  멈춤" else "▶  시작"
        findViewById<TextView>(R.id.status).text = if (running) "감시 중" else "대기 중"

        val sb = StringBuilder()
        val quotes = synchronized(PriceService.lastQuotes) { PriceService.lastQuotes.values.toList() }
        if (quotes.isEmpty()) {
            for (s in prefs.symbolList()) sb.append(s.name).append("  —  아직 조회 전\n")
        } else for (q in quotes) {
            val sign = if (q.changePct >= 0) "+" else ""
            sb.append(String.format(Locale.US, "%-8s  %9.2f   %s%.2f%%\n", q.name, q.price, sign, q.changePct))
        }
        findViewById<TextView>(R.id.prices).text = sb.toString().trimEnd()
        findViewById<TextView>(R.id.log).text = PriceService.lastLog
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, receiver, IntentFilter(PriceService.BROADCAST), ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
        saveSettings()
    }

    override fun onDestroy() {
        testTts?.shutdown()
        super.onDestroy()
    }
}

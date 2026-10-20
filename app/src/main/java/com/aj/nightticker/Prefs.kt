package com.aj.nightticker

import android.content.Context

/** 앱 설정. 종목은 "TSLA=테슬라, PLTR=팔란티어" 형식의 한 줄 문자열. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("nightticker", Context.MODE_PRIVATE)

    var apiKey: String
        get() = p.getString("apiKey", DEFAULT_KEY) ?: DEFAULT_KEY
        set(v) = p.edit().putString("apiKey", v.trim()).apply()

    var symbols: String
        get() = p.getString("symbols", "TSLA=테슬라, PLTR=팔란티어") ?: ""
        set(v) = p.edit().putString("symbols", v).apply()

    var intervalMin: Int
        get() = p.getInt("intervalMin", 5)
        set(v) = p.edit().putInt("intervalMin", v).apply()

    /** true: 변동 있을 때만 말하기, false: 주기마다 매번 말하기 */
    var onlyOnChange: Boolean
        get() = p.getBoolean("onlyOnChange", true)
        set(v) = p.edit().putBoolean("onlyOnChange", v).apply()

    var thresholdPct: Float
        get() = p.getFloat("thresholdPct", 1f)
        set(v) = p.edit().putFloat("thresholdPct", v).apply()

    var marketOnly: Boolean
        get() = p.getBoolean("marketOnly", true)
        set(v) = p.edit().putBoolean("marketOnly", v).apply()

    var speechRate: Float
        get() = p.getFloat("speechRate", 0.95f)
        set(v) = p.edit().putFloat("speechRate", v).apply()

    data class Sym(val code: String, val name: String)

    fun symbolList(): List<Sym> = symbols.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map {
        val parts = it.split("=").map { s -> s.trim() }
        val code = parts[0].uppercase()
        Sym(code, parts.getOrNull(1)?.takeIf { s -> s.isNotEmpty() } ?: code)
    }

    companion object {
        val DEFAULT_KEY: String = BuildConfig.FINNHUB_KEY
    }
}

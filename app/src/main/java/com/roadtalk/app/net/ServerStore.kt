package com.roadtalk.app.net

import android.content.Context

/** 별도 Car-ind CHAT 서버 주소. 비어 있으면 Firebase(또는 시뮬레이션)를 쓴다. */
class ServerStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("roadtalk", Context.MODE_PRIVATE)

    fun load(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun save(url: String?) {
        prefs.edit().putString(KEY, url).apply()
    }

    companion object {
        private const val KEY = "server_url"

        /** "192.168.0.10:8080" -> "http://192.168.0.10:8080". 형식이 아니면 null */
        fun normalize(input: String): String? {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            return withScheme.takeIf { Regex("""https?://[\w.\-]+(:\d{1,5})?""").matches(it) }
        }
    }
}

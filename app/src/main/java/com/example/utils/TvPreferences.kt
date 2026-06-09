package com.example.utils

import android.content.Context

object TvPreferences {
    private const val PREFS_NAME = "farabi_tv_preferences"
    private const val KEY_LAST_WATCHED_URL = "last_watched_url"
    private const val KEY_RECENT_CHANNELS = "recent_channels"

    fun saveLastWatchedUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_WATCHED_URL, url)
            .apply()
    }

    fun getLastWatchedUrl(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_WATCHED_URL, null)
    }

    fun saveRecentUrls(context: Context, urls: List<String>) {
        val joined = urls.joinToString(",")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT_CHANNELS, joined)
            .apply()
    }

    fun getRecentUrls(context: Context): List<String> {
        val joined = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECENT_CHANNELS, null) ?: return emptyList()
        if (joined.isEmpty()) return emptyList()
        return joined.split(",")
    }
}

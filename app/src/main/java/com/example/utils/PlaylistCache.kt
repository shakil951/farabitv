package com.example.utils

import android.content.Context
import java.io.File
import java.io.InputStream

object PlaylistCache {
    private const val FILE_NAME = "playlist_cache.m3u"

    fun save(context: Context, inputStream: InputStream) {
        try {
            val file = File(context.cacheDir, FILE_NAME)
            file.outputStream().use { output ->
                inputStream.copyTo(output)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun openInputStream(context: Context): InputStream? {
        val file = File(context.cacheDir, FILE_NAME)
        return if (file.exists()) file.inputStream() else null
    }
}

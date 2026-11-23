package ir.soundrecorder.pro.storage

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AudioStorage {
    fun directory(context: Context, source: String): File {
        val base = context.getExternalFilesDir("Recordings") ?: context.filesDir
        return File(base, source).apply { mkdirs() }
    }

    fun newM4a(context: Context, source: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = System.currentTimeMillis() % 1000
        return File(directory(context, source), "${source}_${stamp}_$suffix.m4a")
    }

    fun list(context: Context): List<File> = listOf("environment", "internal")
        .flatMap { directory(context, it).listFiles()?.toList().orEmpty() }
        .filter { it.isFile && it.extension.equals("m4a", true) }
        .sortedByDescending { it.lastModified() }
}

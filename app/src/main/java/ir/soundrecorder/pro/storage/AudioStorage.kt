package ir.soundrecorder.pro.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class RecordingFile(val uri: Uri, val name: String, val size: Long, val date: Long,
    val duration: Long, val privateFile: File? = null) {
    val mime: String get() = if (name.endsWith(".wav", true)) "audio/wav" else "audio/mp4"
}

object AudioStorage {
    const val PATH = "Music/SoundRecorderPro/"
    private val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private fun base(context: Context) = context.getExternalFilesDir("Recordings") ?: File(context.filesDir, "Recordings")
    fun temporary(context: Context, source: String, extension: String): File {
        val dir = File(base(context), source).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "${source}_${stamp}_${UUID.randomUUID().toString().take(8)}.$extension.partial")
    }

    // Finalize locally first; if MediaStore fails, the playable private copy is kept.
    fun finish(context: Context, temp: File): String? {
        check(temp.exists() && temp.length() > 0) { "فایل ضبط خالی است" }
        val file = File(temp.parentFile, temp.name.removeSuffix(".partial"))
        check(temp.renameTo(file)) { "نهایی‌سازی فایل ناموفق بود" }
        return try {
            publish(context, file)
            null
        } catch (e: Exception) {
            "فایل در حافظه خصوصی محفوظ است؛ انتقال به پوشه Music ناموفق بود: ${e.localizedMessage}"
        }
    }

    private fun publish(context: Context, file: File) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, if (file.extension.equals("wav", true)) "audio/wav" else "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, PATH)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collection, values)) { "MediaStore insert failed" }
        try {
            val output = checkNotNull(resolver.openOutputStream(uri, "w"))
            output.use { out -> file.inputStream().use { it.copyTo(out) } }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) == 1)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        file.delete()
    }

    fun export(context: Context, item: RecordingFile) {
        item.privateFile?.let { publish(context, it) }
    }

    fun list(context: Context): List<RecordingFile> {
        val result = mutableListOf<RecordingFile>()
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.DURATION)
        runCatching { context.contentResolver.query(collection, columns,
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0",
            arrayOf(PATH), null)?.use { c ->
            while (c.moveToNext()) result += RecordingFile(ContentUris.withAppendedId(collection, c.getLong(0)),
                c.getString(1), c.getLong(2), c.getLong(3) * 1000, c.getLong(4))
        } }
        val roots = listOfNotNull(context.getExternalFilesDir("Recordings"), File(context.filesDir, "Recordings"), context.filesDir).distinct()
        val privateFiles = roots.flatMap { root -> listOf("environment", "internal").flatMap { source ->
            File(root, source).listFiles()?.toList().orEmpty()
        }}.distinctBy { it.absolutePath }.filter { it.isFile && it.length() > 0 && it.extension.lowercase(Locale.ROOT) in listOf("m4a", "wav") }
        privateFiles.forEach { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val duration = runCatching {
                val retriever = MediaMetadataRetriever()
                try { retriever.setDataSource(file.absolutePath); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
                finally { retriever.release() }
            }.getOrDefault(0L)
            result += RecordingFile(uri, file.name, file.length(), file.lastModified(), duration, file)
        }
        return result.sortedByDescending { it.date }
    }

    fun delete(context: Context, item: RecordingFile) {
        check(item.privateFile?.delete() ?: (context.contentResolver.delete(item.uri, null, null) == 1)) { "حذف فایل ناموفق بود" }
    }

    fun rename(context: Context, item: RecordingFile, name: String) {
        val baseName = name.trim().removeSuffix(".${item.name.substringAfterLast('.')}")
        require(baseName.isNotBlank() && baseName !in listOf(".", "..") && baseName.none { it in "/\\:*?\"<>|" || it.isISOControl() }) { "نام فایل معتبر نیست" }
        val newName = "$baseName.${item.name.substringAfterLast('.')}"
        if (newName == item.name) return
        if (item.privateFile != null) {
            val target = File(item.privateFile.parentFile, newName)
            check(!target.exists() && item.privateFile.renameTo(target)) { "تغییر نام ناموفق بود" }
        } else check(context.contentResolver.update(item.uri, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, newName)
        }, null, null) == 1) { "تغییر نام ناموفق بود" }
    }
}

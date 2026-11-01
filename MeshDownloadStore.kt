package com.example.mesh

import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Only consented endpoint files reach this store; relay packets never do. */
class MeshDownloadStore(private val context: Context) {
    private val prefs get() = context.getSharedPreferences("mesh-download-history", Context.MODE_PRIVATE)
    fun history(): List<SavedMeshFile> = runCatching {
        val array = JSONArray(prefs.getString("files", "[]"))
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            SavedMeshFile(it.getString("id"), it.getString("name"), it.getString("uri"), it.getString("mime"), it.getLong("size"))
        } }
    }.getOrDefault(emptyList())

    suspend fun save(completed: ChunkManager.Completed): SavedMeshFile = withContext(Dispatchers.IO) {
        val metadata = completed.metadata
        history().firstOrNull { it.id == metadata.id.toString() }?.let { return@withContext it }
        val name = safeDownloadName(metadata.fileName)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val resolver = context.contentResolver
        var uri: Uri? = null
        var legacy: File? = null
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SR-Mesh/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })) { "Cannot create Downloads entry" }
                checkNotNull(resolver.openOutputStream(uri, "w")).use { output -> completed.file.inputStream().use { it.copyTo(output, 512 * 1024) } }
                verifyStoredFile(checkNotNull(resolver.openInputStream(uri)), metadata.size, metadata.sha256)
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            } else {
                check(context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    "Allow storage permission to save in Downloads"
                }
                @Suppress("DEPRECATION")
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SR-Mesh")
                check(folder.isDirectory || folder.mkdirs())
                legacy = File(folder, "${metadata.id}-$name.part")
                completed.file.inputStream().use { input -> legacy.outputStream().use { input.copyTo(it, 512 * 1024) } }
                verifyStoredFile(legacy.inputStream(), metadata.size, metadata.sha256)
                val destination = File(folder, "${metadata.id}-$name")
                check(!destination.exists() && legacy.renameTo(destination))
                legacy = destination
                uri = FileProvider.getUriForFile(context, "${context.packageName}.downloads", destination)
            }
            val result = SavedMeshFile(metadata.id.toString(), name, checkNotNull(uri).toString(), mime, metadata.size)
            val files = (listOf(result) + history()).distinctBy { it.id }.take(1000)
            val json = JSONArray().apply { files.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("uri", it.uri).put("mime", it.mime).put("size", it.size)) } }
            check(prefs.edit().putString("files", json.toString()).commit()) { "Cannot save download history" }
            result
        } catch (error: Exception) {
            if (Build.VERSION.SDK_INT >= 29) uri?.let { runCatching { resolver.delete(it, null, null) } }
            legacy?.delete()
            throw error
        }
    }

    fun openFile(file: SavedMeshFile) {
        val uri = Uri.parse(file.uri)
        launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(file.name, uri)
        }, "No compatible app found, or the file was moved / फ़ाइल खोलने के लिए ऐप उपलब्ध नहीं है")
    }
    fun openFolder() {
        val folder = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/SR-Mesh")
        try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder), "Open Downloads > SR-Mesh in your file manager") }
    }
    private fun launch(intent: Intent, message: String) {
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
}

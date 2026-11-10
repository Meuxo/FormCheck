package com.meuxo.formcheck.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.meuxo.formcheck.domain.model.SessionParams
import com.meuxo.formcheck.domain.model.SessionResult
import com.meuxo.formcheck.tracking.TrackedClip
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns `filesDir/sessions/{id}/` (spec 5). Media is always copied here on import or
 * record; a picker URI is never stored as the only copy.
 *
 *   source.mp4     the video
 *   params.json    inputs chosen before analysis
 *   track.bin.gz   per-frame landmarks + ball positions ([TrackCodec])
 *   thumb.jpg      thumbnail
 *   result.json    full [SessionResult]
 */
@Singleton
class SessionFiles @Inject constructor(@ApplicationContext private val context: Context) {

    private val root get() = File(context.filesDir, "sessions").apply { mkdirs() }

    fun dir(id: Long) = File(root, id.toString()).apply { mkdirs() }
    fun media(id: Long) = File(dir(id), "source.mp4")
    fun params(id: Long) = File(dir(id), "params.json")
    fun track(id: Long) = File(dir(id), "track.bin.gz")
    fun thumb(id: Long) = File(dir(id), "thumb.jpg")
    fun result(id: Long) = File(dir(id), "result.json")
    fun export(id: Long) = File(dir(id), "annotated.mp4")

    /** Copies picked media into the session folder. Returns bytes copied. */
    suspend fun importFromUri(id: Long, uri: Uri): Long = withContext(Dispatchers.IO) {
        val target = media(id)
        val tmp = File(target.parentFile, "source.part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
        } ?: error("Could not open the selected video")
        if (!tmp.renameTo(target)) error("Could not save the video")
        target.length()
    }

    /** Moves a camera recording (written to cache) into the session folder. */
    suspend fun importRecording(id: Long, recorded: File): Long = withContext(Dispatchers.IO) {
        val target = media(id)
        if (!recorded.renameTo(target)) {
            recorded.copyTo(target, overwrite = true); recorded.delete()
        }
        target.length()
    }

    suspend fun writeParams(id: Long, p: SessionParams) = withContext(Dispatchers.IO) {
        params(id).writeText(json.encodeToString(SessionParams.serializer(), p))
    }

    suspend fun readParams(id: Long): SessionParams? = withContext(Dispatchers.IO) {
        params(id).takeIf { it.exists() }?.let { json.decodeFromString(SessionParams.serializer(), it.readText()) }
    }

    suspend fun writeTrack(id: Long, clip: TrackedClip) = withContext(Dispatchers.IO) {
        val tmp = File(dir(id), "track.part")
        tmp.outputStream().use { TrackCodec.write(clip, it) }
        tmp.renameTo(track(id))
    }

    suspend fun readTrack(id: Long): TrackedClip? = withContext(Dispatchers.IO) {
        track(id).takeIf { it.exists() }?.inputStream()?.use { TrackCodec.read(it) }
    }

    suspend fun writeResult(id: Long, r: SessionResult) = withContext(Dispatchers.IO) {
        result(id).writeText(json.encodeToString(SessionResult.serializer(), r))
    }

    suspend fun readResult(id: Long): SessionResult? = withContext(Dispatchers.IO) {
        result(id).takeIf { it.exists() }?.let { json.decodeFromString(SessionResult.serializer(), it.readText()) }
    }

    suspend fun writeThumb(id: Long, bmp: Bitmap) = withContext(Dispatchers.IO) {
        thumb(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
    }

    fun sizeOf(id: Long): Long = dir(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { File(root, id.toString()).deleteRecursively() }

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }
    }
}

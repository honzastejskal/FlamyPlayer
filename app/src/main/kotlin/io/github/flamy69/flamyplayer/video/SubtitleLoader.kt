package io.github.flamy69.flamyplayer.video

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.net.toUri
import io.github.flamy69.flamyplayer.data.SubtitleFiles
import io.github.flamy69.flamyplayer.data.VideoFiles
import io.github.flamy69.flamyplayer.network.SmbClient
import java.io.File
import java.util.Locale

/** A subtitle file that was read into memory and converted to UTF-8 text. */
class LoadedSubtitle(
    /** Shown in the track list and used to find the track again; unique within a video. */
    val label: String,
    val language: String?,
    val mimeType: String,
    val text: String,
) {
    /** A copy whose label differs from the labels of [others]. */
    fun withUniqueLabel(others: List<LoadedSubtitle>): LoadedSubtitle {
        if (others.none { it.label == label }) return this
        val number = (2..others.size + 2).first { n -> others.none { it.label == "$label $n" } }
        return LoadedSubtitle("$label $number", language, mimeType, text)
    }

    /** The text wrapped in a `data:` URI, which the player can read without touching storage. */
    val uri: Uri
        get() = ("data:$mimeType;base64," + Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .toUri()
}

/**
 * Reads the subtitle files that sit next to a video. Reading them up front makes it possible to
 * fix their encoding: subtitles that are not UTF-8 would otherwise lose all accented letters.
 *
 * All methods block; call them from a background thread.
 */
class SubtitleLoader(private val context: Context, private val smb: SmbClient) {

    fun load(item: VideoItem): List<LoadedSubtitle> = try {
        val found = when (val source = item.subtitles) {
            SubtitleSource.None -> emptyList()
            is SubtitleSource.Device -> loadFromDevice(source)
            is SubtitleSource.Network -> loadFromNetwork(source)
        }
        found.fold(emptyList<LoadedSubtitle>()) { unique, subtitle -> unique + subtitle.withUniqueLabel(unique) }
    } catch (_: Exception) {
        // Subtitles are a bonus; the video plays without them.
        emptyList()
    }

    /** Reads a subtitle file the viewer picked by hand. */
    fun loadDocument(uri: Uri): LoadedSubtitle? = try {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment
            ?: "subtitles.srt"
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            // One byte more than the limit, to notice files that are too big.
            val buffer = ByteArray(MAX_BYTES + 1)
            var length = 0
            while (length < buffer.size) {
                val read = input.read(buffer, length, buffer.size - length)
                if (read < 0) break
                length += read
            }
            buffer.copyOf(length)
        }
        if (bytes == null || bytes.size > MAX_BYTES) null else picked(name, bytes)
    } catch (_: Exception) {
        null
    }

    /** Reads a subtitle file the viewer picked by hand in the folders shown on a TV. */
    fun loadFromPlace(browser: SubtitleFileBrowser, place: SubtitlePlace, name: String): LoadedSubtitle? = try {
        browser.read(place, name, MAX_BYTES)?.let { picked(name, it) }
    } catch (_: Exception) {
        null
    }

    private fun picked(name: String, bytes: ByteArray): LoadedSubtitle? {
        // Picked by hand, so it is a subtitle file whatever it is called.
        val fileName = if (SubtitleFiles.format(name) == null) "$name.srt" else name
        return subtitle(label = name, language = null, fileName = fileName, bytes = bytes)
    }

    private fun loadFromDevice(source: SubtitleSource.Device): List<LoadedSubtitle> {
        val video = File(source.videoPath)
        val directory = video.parentFile ?: return emptyList()
        // Depending on the Android version the listing may leave out files that are not media,
        // so fall back to asking for the usual names one by one.
        val names = SubtitleFiles.matching(video.name, directory.list()?.toList().orEmpty())
            .ifEmpty { usualNames(video.name).filter { File(directory, it).isFile } }
        return names.take(MAX_FILES).mapNotNull { name ->
            val file = File(directory, name)
            if (file.length() > MAX_BYTES) return@mapNotNull null
            subtitle(video.name, name, file.readBytes())
        }
    }

    private fun loadFromNetwork(source: SubtitleSource.Network): List<LoadedSubtitle> =
        source.fileNames.take(MAX_FILES).mapNotNull { name ->
            val bytes = smb.readFile(source.server, source.directory.first(), source.directory.drop(1) + name, MAX_BYTES)
                ?: return@mapNotNull null
            subtitle(source.videoName, name, bytes)
        }

    private fun subtitle(videoName: String, fileName: String, bytes: ByteArray): LoadedSubtitle? {
        val tag = SubtitleFiles.tag(videoName, fileName)
        val language = SubtitleFiles.language(tag)
        // "SRT" for Film.cs.srt (the language is shown separately), "SRT forced" for
        // Film.cs.forced.srt.
        val details = tag.split('.', '_', '-', ' ').filter { it.isNotEmpty() }.drop(if (language != null) 1 else 0)
        return subtitle(
            label = (listOf(VideoFiles.extension(fileName).uppercase()) + details).joinToString(" "),
            // A file without a language in its name is most likely in the viewer's own language.
            language = language ?: deviceLanguage,
            fileName = fileName,
            bytes = bytes,
        )
    }

    private fun subtitle(label: String, language: String?, fileName: String, bytes: ByteArray): LoadedSubtitle? {
        val format = SubtitleFiles.format(fileName) ?: return null
        val text = SubtitleFiles.decode(bytes, SubtitleFiles.legacyCharset(language ?: deviceLanguage))
        return LoadedSubtitle(label, language, format.mimeType, text)
    }

    private fun usualNames(videoName: String): List<String> {
        val base = VideoFiles.baseName(videoName)
        val tags = listOf("", deviceLanguage, "en", "eng", "cs", "cze", "cz", "sk").distinct()
        return EXTENSIONS.flatMap { extension ->
            tags.map { tag -> if (tag.isEmpty()) "$base.$extension" else "$base.$tag.$extension" }
        }
    }

    private val deviceLanguage: String get() = Locale.getDefault().language

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        const val MAX_FILES = 8
        val EXTENSIONS = listOf("srt", "ass", "ssa", "vtt")
    }
}

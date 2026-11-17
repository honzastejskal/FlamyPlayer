package io.github.flamy69.flamyplayer.video

import io.github.flamy69.flamyplayer.data.NaturalOrder
import io.github.flamy69.flamyplayer.data.SubtitleFiles
import io.github.flamy69.flamyplayer.network.SmbClient
import io.github.flamy69.flamyplayer.network.SmbServer
import java.io.File

/** A folder in which to look for a subtitle file. */
sealed interface SubtitlePlace {
    val name: String

    /** Whether there is a folder above this one to go to. */
    val canGoUp: Boolean

    /** A folder of a share; [directory] is the share name followed by the folders. */
    data class Network(val server: SmbServer, val directory: List<String>) : SubtitlePlace {
        override val name: String get() = directory.last()
        override val canGoUp: Boolean get() = directory.size > 1
    }

    /** A folder of the device. */
    data class Device(val directory: File) : SubtitlePlace {
        override val name: String get() = directory.name
        override val canGoUp: Boolean get() = directory.parentFile?.canRead() == true
    }
}

/** One line of a [SubtitlePlace]: a folder or a subtitle file. */
class SubtitlePlaceEntry(val name: String, val isDirectory: Boolean)

/**
 * Looks for subtitle files by hand, for TVs, which have no file picker of the system. Starts
 * in the folder of the video and goes from there, through the same shares as the video.
 *
 * [list] and [read] block; call them from a background thread.
 */
class SubtitleFileBrowser(private val smb: SmbClient) {

    /** The folder of the video, or `null` when it does not come from a folder (media servers, DVDs). */
    fun start(source: SubtitleSource): SubtitlePlace? = when (source) {
        SubtitleSource.None -> null
        is SubtitleSource.Device -> File(source.videoPath).parentFile?.let { SubtitlePlace.Device(it) }
        is SubtitleSource.Network -> source.directory.takeIf { it.isNotEmpty() }
            ?.let { SubtitlePlace.Network(source.server, it) }
    }

    fun up(place: SubtitlePlace): SubtitlePlace = when (place) {
        is SubtitlePlace.Network -> if (place.canGoUp) place.copy(directory = place.directory.dropLast(1)) else place
        is SubtitlePlace.Device -> place.directory.parentFile?.takeIf { it.canRead() }?.let { SubtitlePlace.Device(it) }
            ?: place
    }

    fun into(place: SubtitlePlace, folder: String): SubtitlePlace = when (place) {
        is SubtitlePlace.Network -> place.copy(directory = place.directory + folder)
        is SubtitlePlace.Device -> SubtitlePlace.Device(File(place.directory, folder))
    }

    /** The folders and subtitle files of [place], folders first. */
    fun list(place: SubtitlePlace): List<SubtitlePlaceEntry> {
        val entries = when (place) {
            is SubtitlePlace.Network -> smb.list(place.server, place.directory.first(), place.directory.drop(1))
                .map { SubtitlePlaceEntry(it.name, it.isDirectory) }
            is SubtitlePlace.Device -> place.directory.listFiles().orEmpty()
                .map { SubtitlePlaceEntry(it.name, it.isDirectory) }
        }
        return entries
            .filter { entry ->
                // Leaves out hidden folders and the housekeeping folders of Windows and NAS boxes.
                if (entry.isDirectory) entry.name.first() !in ".$@#" else SubtitleFiles.format(entry.name) != null
            }
            .sortedWith(compareByDescending<SubtitlePlaceEntry> { it.isDirectory }.thenBy(NaturalOrder) { it.name })
    }

    /** The content of the file [name] in [place], or `null` if it is bigger than [maxBytes]. */
    fun read(place: SubtitlePlace, name: String, maxBytes: Int): ByteArray? = when (place) {
        is SubtitlePlace.Network ->
            smb.readFile(place.server, place.directory.first(), place.directory.drop(1) + name, maxBytes)
        is SubtitlePlace.Device -> File(place.directory, name).takeIf { it.length() <= maxBytes }?.readBytes()
    }
}

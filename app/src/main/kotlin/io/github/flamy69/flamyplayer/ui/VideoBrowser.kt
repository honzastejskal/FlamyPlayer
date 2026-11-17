package io.github.flamy69.flamyplayer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.flamy69.flamyplayer.FlamyApp
import io.github.flamy69.flamyplayer.data.DvdFiles
import io.github.flamy69.flamyplayer.data.NaturalOrder
import io.github.flamy69.flamyplayer.data.SubtitleFiles
import io.github.flamy69.flamyplayer.data.Video
import io.github.flamy69.flamyplayer.data.VideoFiles
import io.github.flamy69.flamyplayer.data.VideoFolder
import io.github.flamy69.flamyplayer.data.VideoLibrary
import io.github.flamy69.flamyplayer.data.VideoSettings
import io.github.flamy69.flamyplayer.data.groupVideosByFolder
import io.github.flamy69.flamyplayer.network.Dlna
import io.github.flamy69.flamyplayer.network.FoundDevice
import io.github.flamy69.flamyplayer.network.JoinUri
import io.github.flamy69.flamyplayer.network.MediaEntry
import io.github.flamy69.flamyplayer.network.MediaFolder
import io.github.flamy69.flamyplayer.network.MediaServer
import io.github.flamy69.flamyplayer.network.NetworkScan
import io.github.flamy69.flamyplayer.network.SecretStore
import io.github.flamy69.flamyplayer.network.SmbAddress
import io.github.flamy69.flamyplayer.network.SmbEntry
import io.github.flamy69.flamyplayer.network.SmbException
import io.github.flamy69.flamyplayer.network.SmbServer
import io.github.flamy69.flamyplayer.network.SmbUri
import io.github.flamy69.flamyplayer.video.SubtitleSource
import io.github.flamy69.flamyplayer.video.VideoItem
import io.github.flamy69.flamyplayer.video.VideoPlayerActivity
import io.github.flamy69.flamyplayer.video.VideoQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Where in the video section the user is. */
sealed interface VideoLocation {
    /** The overview: network locations and the folders of the device. */
    data object Home : VideoLocation

    /** A folder of the device. */
    data class Device(val folder: String) : VideoLocation

    /** A folder of a network location; [path] is relative to the root of that location. */
    data class Network(val serverId: String, val path: List<String>) : VideoLocation

    /** A folder of a media server; [path] leads to it from the top and is empty there. */
    data class Stream(val server: MediaServer, val path: List<MediaFolder>) : VideoLocation
}

/** The content of the network folder that is open. */
sealed interface NetworkListing {
    data object Loading : NetworkListing
    class Ready(val entries: List<SmbEntry>) : NetworkListing
    class Failed(val reason: SmbException.Reason, val detail: String) : NetworkListing
}

/** The content of the folder of a media server that is open. */
sealed interface StreamListing {
    data object Loading : StreamListing
    class Ready(val entries: List<MediaEntry>) : StreamListing
    class Failed(val detail: String) : StreamListing
}

/** The search for computers, NAS boxes and media servers on the local network. */
sealed interface Discovery {
    /** Not searched yet. */
    data object Idle : Discovery
    data object Searching : Discovery

    /** The phone is not on Wi-Fi, so there is no local network to search. */
    data object NoWifi : Discovery
    class Done(val devices: List<FoundDevice>, val mediaServers: List<MediaServer>) : Discovery
}

/** Access to devices on the local network needs the user's consent from Android 17 on. */
object LocalNetwork {

    val permission: String?
        get() = if (Build.VERSION.SDK_INT >= 37) Manifest.permission.ACCESS_LOCAL_NETWORK else null

    fun isAllowed(context: Context): Boolean {
        val needed = permission ?: return true
        return ContextCompat.checkSelfPermission(context, needed) == PackageManager.PERMISSION_GRANTED
    }
}

/** State and actions of the video section: the videos on the device and the network folders. */
class VideoBrowser(private val app: FlamyApp, private val scope: CoroutineScope) {

    val settings: VideoSettings = app.videoSettings

    private val _hasPermission = MutableStateFlow(VideoLibrary.hasPermission(app))
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val _folders = MutableStateFlow<List<VideoFolder>?>(null)

    /** The folders of the device that contain videos; `null` until they have been loaded. */
    val folders: StateFlow<List<VideoFolder>?> = _folders.asStateFlow()

    private val _location = MutableStateFlow<VideoLocation>(VideoLocation.Home)
    val location: StateFlow<VideoLocation> = _location.asStateFlow()

    private val _listing = MutableStateFlow<NetworkListing>(NetworkListing.Loading)
    val listing: StateFlow<NetworkListing> = _listing.asStateFlow()

    private val _streamListing = MutableStateFlow<StreamListing>(StreamListing.Loading)
    val streamListing: StateFlow<StreamListing> = _streamListing.asStateFlow()

    private val _discovery = MutableStateFlow<Discovery>(Discovery.Idle)

    /** Machines with shared folders that were found on the local network. */
    val discovery: StateFlow<Discovery> = _discovery.asStateFlow()

    private var discoveryJob: Job? = null

    /**
     * After going up: the key of the line that leads back to the folder that was left, so that
     * the remote control of a TV can carry on from there. `null` after going down.
     */
    var cameFrom: String? = null
        private set

    /** Folders seen in this session; going back up shows them without asking the server again. */
    private val cache = HashMap<VideoLocation.Network, List<SmbEntry>>()
    private val streamCache = HashMap<VideoLocation.Stream, List<MediaEntry>>()

    private var deviceJob: Job? = null
    private var listJob: Job? = null
    private var deviceWanted = false

    /** Loads the videos of the device. Does nothing until the video section was opened once. */
    fun refreshDevice(force: Boolean = false) {
        if (force) deviceWanted = true
        val granted = VideoLibrary.hasPermission(app)
        _hasPermission.value = granted
        if (!deviceWanted || !granted || deviceJob?.isActive == true) return
        deviceJob = scope.launch {
            _folders.value = withContext(Dispatchers.IO) { groupVideosByFolder(VideoLibrary.load(app)) }
        }
    }

    /** Searches the local network; once per session unless [again] is set. */
    fun discover(again: Boolean = false) {
        if (discoveryJob?.isActive == true) return
        if (!again && _discovery.value != Discovery.Idle) return
        _discovery.value = Discovery.Searching
        discoveryJob = scope.launch {
            val found = try {
                app.localNetwork.scan()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                NetworkScan(emptyList(), emptyList())
            }
            _discovery.value = if (found == null) {
                Discovery.NoWifi
            } else {
                Discovery.Done(
                    devices = found.devices.sortedWith(compareBy(NaturalOrder) { it.host }),
                    mediaServers = found.mediaServers.sortedWith(compareBy(NaturalOrder) { it.name }),
                )
            }
        }
    }

    fun openFolder(folder: VideoFolder) {
        cameFrom = null
        _location.value = VideoLocation.Device(folder.path)
    }

    /** Opens a network location where the user left it the last time. */
    fun openServer(server: SmbServer) {
        cameFrom = null
        show(VideoLocation.Network(server.id, settings.lastPath(server.id)), fallBackToRoot = true)
    }

    fun openMediaServer(server: MediaServer) {
        cameFrom = null
        showStream(VideoLocation.Stream(server, emptyList()))
    }

    fun openStreamFolder(folder: MediaEntry) {
        val current = _location.value as? VideoLocation.Stream ?: return
        cameFrom = null
        showStream(current.copy(path = current.path + MediaFolder(folder.id, folder.title)))
    }

    fun openDirectory(name: String) {
        val current = _location.value as? VideoLocation.Network ?: return
        cameFrom = null
        show(current.copy(path = current.path + name))
    }

    /** Goes one level up. Returns `false` when already at the overview. */
    fun up(): Boolean {
        val current = _location.value
        cameFrom = when (current) {
            VideoLocation.Home -> null
            is VideoLocation.Device -> deviceFolderKey(current.folder)
            is VideoLocation.Network -> current.path.lastOrNull() ?: serverKey(current.serverId)
            is VideoLocation.Stream -> current.path.lastOrNull()?.id ?: mediaServerKey(current.server)
        }
        when (current) {
            VideoLocation.Home -> return false
            is VideoLocation.Device -> _location.value = VideoLocation.Home
            is VideoLocation.Network -> if (current.path.isEmpty()) {
                listJob?.cancel()
                _location.value = VideoLocation.Home
            } else {
                show(current.copy(path = current.path.dropLast(1)))
            }
            is VideoLocation.Stream -> if (current.path.isEmpty()) {
                listJob?.cancel()
                _location.value = VideoLocation.Home
            } else {
                showStream(current.copy(path = current.path.dropLast(1)))
            }
        }
        return true
    }

    /** Asks the server for the open folder again. */
    fun reload() {
        when (val current = _location.value) {
            is VideoLocation.Network -> {
                cache.remove(current)
                show(current)
            }
            is VideoLocation.Stream -> {
                streamCache.remove(current)
                showStream(current)
            }
            else -> Unit
        }
    }

    /**
     * Saves a new or edited network location and, if it is new, opens it. Returns `false` if
     * [address] cannot be understood. An empty [password] keeps the stored one, unless the user
     * name was removed as well.
     */
    fun saveServer(existing: SmbServer?, name: String, address: String, user: String, password: String): Boolean {
        val (host, root) = SmbAddress.parse(address) ?: return false
        val id = existing?.id ?: UUID.randomUUID().toString()
        scope.launch {
            val secret = when {
                user.isBlank() -> ""
                password.isNotEmpty() -> withContext(Dispatchers.IO) { SecretStore.encrypt(password) }.orEmpty()
                else -> existing?.secret.orEmpty()
            }
            val server = SmbServer(id, name.trim(), host, root, user.trim(), secret)
            if (existing != null && (existing.host != host || existing.root != root)) {
                settings.setLastPath(id, emptyList())
            }
            settings.saveServer(server)
            forget(id)
            // Shows right away whether the address and the password are right.
            if (existing == null) openServer(server)
        }
        return true
    }

    fun removeServer(server: SmbServer) {
        settings.removeServer(server.id)
        forget(server.id)
    }

    fun playDeviceVideo(context: Context, folder: VideoFolder, video: Video) {
        val items = folder.videos.map {
            VideoItem(
                uri = it.uri,
                title = VideoFiles.baseName(it.fileName),
                key = it.path,
                subtitles = SubtitleSource.Device(it.path),
            )
        }
        play(context, items, folder.videos.indexOf(video))
    }

    /** Plays [entry] of the open network folder; the other videos of the folder follow it. */
    fun playNetworkVideo(context: Context, entry: SmbEntry) {
        val current = _location.value as? VideoLocation.Network ?: return
        val server = settings.server(current.serverId) ?: return
        val entries = (_listing.value as? NetworkListing.Ready)?.entries ?: return
        val directory = server.root + current.path
        if (directory.isEmpty()) return

        val files = entries.filterNot { it.isDirectory }
        val names = files.map { it.name }
        val videos = files.filter { it.isVideo }
        val items = videos.map { video ->
            if (video.parts.isNotEmpty()) {
                // The pieces of a DVD title are read one after the other, as one film.
                return@map VideoItem(
                    uri = JoinUri.build(
                        video.parts.map { JoinUri.Part(SmbUri.build(server.id, directory + it.name), it.size) },
                    ),
                    title = video.name,
                    key = networkKey(server.id, directory, video.name),
                    subtitles = SubtitleSource.None,
                )
            }
            VideoItem(
                uri = SmbUri.build(server.id, directory + video.name),
                title = VideoFiles.baseName(video.name),
                key = networkKey(server.id, directory, video.name),
                subtitles = SubtitleSource.Network(
                    server = server,
                    directory = directory,
                    videoName = video.name,
                    fileNames = SubtitleFiles.matching(video.name, names),
                ),
            )
        }
        play(context, items, videos.indexOf(entry))
    }

    /** Plays [entry] of the open media server folder; the other videos of the folder follow it. */
    fun playStreamVideo(context: Context, entry: MediaEntry) {
        val current = _location.value as? VideoLocation.Stream ?: return
        val entries = (_streamListing.value as? StreamListing.Ready)?.entries ?: return
        val videos = entries.filter { !it.isFolder && it.url != null }
        val items = videos.map { video ->
            VideoItem(
                uri = video.url.orEmpty().toUri(),
                title = video.title,
                key = streamKey(current.server, video),
                // A media server only hands out what is inside the video file.
                subtitles = SubtitleSource.None,
            )
        }
        play(context, items, videos.indexOf(entry))
    }

    /** Keys of the lines of the overview, as used in its list. */
    fun serverKey(serverId: String): String = "server:$serverId"
    fun mediaServerKey(server: MediaServer): String = "stream:" + server.location
    fun deviceFolderKey(path: String): String = "folder:$path"

    /** The key under which the position in a video of a media server is remembered. */
    fun streamKey(server: MediaServer, entry: MediaEntry): String = "stream:" + server.id + "/" + entry.id

    /** The key under which the position in a network video is remembered. */
    fun networkKey(serverId: String, directory: List<String>, fileName: String): String =
        SmbUri.build(serverId, directory + fileName).toString()

    private fun play(context: Context, items: List<VideoItem>, index: Int) {
        if (index < 0) return
        VideoQueue.set(items, index)
        context.startActivity(Intent(context, VideoPlayerActivity::class.java))
    }

    private fun show(target: VideoLocation.Network, fallBackToRoot: Boolean = false) {
        val server = settings.server(target.serverId) ?: return
        listJob?.cancel()
        _location.value = target
        settings.setLastPath(server.id, target.path)

        cache[target]?.let {
            _listing.value = NetworkListing.Ready(it)
            return
        }
        _listing.value = NetworkListing.Loading
        listJob = scope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { fetch(server, target.path) }
                cache[target] = entries
                _listing.value = NetworkListing.Ready(entries)
            } catch (e: SmbException) {
                if (fallBackToRoot && target.path.isNotEmpty() && e.reason == SmbException.Reason.NOT_FOUND) {
                    // The folder that was open last is gone; start from the top instead.
                    show(target.copy(path = emptyList()))
                } else {
                    _listing.value = NetworkListing.Failed(e.reason, e.detail)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _listing.value = NetworkListing.Failed(SmbException.Reason.OTHER, e.toString())
            }
        }
    }

    private fun showStream(target: VideoLocation.Stream) {
        listJob?.cancel()
        _location.value = target

        streamCache[target]?.let {
            _streamListing.value = StreamListing.Ready(it)
            return
        }
        _streamListing.value = StreamListing.Loading
        listJob = scope.launch {
            try {
                val folder = target.path.lastOrNull()?.id ?: Dlna.ROOT
                val entries = withContext(Dispatchers.IO) { Dlna.browse(target.server, folder) }
                streamCache[target] = entries
                _streamListing.value = StreamListing.Ready(entries)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _streamListing.value = StreamListing.Failed(
                    listOfNotNull(e.javaClass.simpleName.ifEmpty { null }, e.message).joinToString(": "),
                )
            }
        }
    }

    private fun fetch(server: SmbServer, path: List<String>): List<SmbEntry> {
        val full = server.root + path
        val entries = if (full.isEmpty()) {
            app.smb.listShares(server).map { SmbEntry(it, isDirectory = true, size = 0) }
        } else {
            app.smb.list(server, full.first(), full.drop(1))
        }
        return joinDvdTitles(
            entries.filter { entry ->
                if (entry.isDirectory) {
                    !entry.name.startsWith('.') && entry.name !in HIDDEN_FOLDERS
                } else {
                    VideoFiles.isVideo(entry.name) || SubtitleFiles.format(entry.name) != null
                }
            },
        ).sortedWith(compareByDescending<SmbEntry> { it.isDirectory }.thenBy(NaturalOrder) { it.name })
    }

    /**
     * In the folder of a copied DVD, shows every film as one line instead of its one gigabyte
     * pieces, and leaves out the menus.
     */
    private fun joinDvdTitles(entries: List<SmbEntry>): List<SmbEntry> {
        val files = entries.filterNot { it.isDirectory }.associateBy { it.name }
        val titles = DvdFiles.titles(files.keys)
        if (titles.isEmpty()) return entries
        val pieces = titles.flatMap { it.parts }.toSet()
        val films = titles.map { title ->
            val parts = title.parts.map(files::getValue)
            SmbEntry("DVD ${title.number}", isDirectory = false, size = parts.sumOf { it.size }, parts = parts)
        }
        return entries.filter { it.isDirectory || (it.name !in pieces && !DvdFiles.isMenu(it.name)) } + films
    }

    private fun forget(serverId: String) {
        cache.keys.removeAll { it.serverId == serverId }
        scope.launch(Dispatchers.IO) { app.smb.disconnect(serverId) }
    }

    private companion object {
        /** Housekeeping folders of Windows and of common NAS systems. */
        val HIDDEN_FOLDERS = setOf("\$RECYCLE.BIN", "System Volume Information", "@eaDir", "#recycle", "#snapshot")
    }
}

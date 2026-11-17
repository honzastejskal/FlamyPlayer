package io.github.flamy69.flamyplayer.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings as SystemSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.flamy69.flamyplayer.R
import io.github.flamy69.flamyplayer.data.Book
import io.github.flamy69.flamyplayer.data.MediaLibrary
import io.github.flamy69.flamyplayer.data.Section
import io.github.flamy69.flamyplayer.data.Settings
import io.github.flamy69.flamyplayer.network.SmbServer

@Composable
fun FlamyPlayerApp(viewModel: MainViewModel) {
    val playerState by viewModel.player.state.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val bookProgress by viewModel.settings.bookProgress.collectAsStateWithLifecycle()

    var section by rememberSaveable { mutableStateOf(viewModel.settings.lastSection) }
    var pickerSection by rememberSaveable { mutableStateOf<Section?>(null) }
    var openBookFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    var playlistsOpen by rememberSaveable { mutableStateOf(false) }
    var openPlaylistPath by rememberSaveable { mutableStateOf<String?>(null) }

    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val openPlaylist = openPlaylistPath?.let { path -> playlists.firstOrNull { it.path == path } }
    val openBook = openBookFolder?.let { folder -> books?.firstOrNull { it.folder == folder } }
    val picker = pickerSection
    val showNowPlaying = nowPlayingOpen && playerState.hasQueue

    val miniPlayer: @Composable (Boolean) -> Unit = { padForNavigationBar ->
        if (playerState.hasQueue) {
            MiniPlayer(
                state = playerState,
                player = viewModel.player,
                onOpen = { nowPlayingOpen = true },
                padForNavigationBar = padForNavigationBar,
            )
        }
    }

    fun playOrPause(book: Book) {
        if (playerState.bookFolder == book.folder && playerState.isPlaying) {
            viewModel.player.togglePlay()
        } else {
            viewModel.playBook(book)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            picker != null -> {
                val root by viewModel.folderTree.collectAsStateWithLifecycle()
                val selected by viewModel.settings.folders(picker).collectAsStateWithLifecycle()
                BackHandler { pickerSection = null }
                FolderPickerScreen(
                    section = picker,
                    root = root,
                    selected = selected,
                    onToggle = { viewModel.toggleFolder(picker, it) },
                    onClearAll = { viewModel.clearFolders(picker) },
                    onClose = { pickerSection = null },
                )
            }

            openBook != null -> {
                BackHandler { openBookFolder = null }
                BookScreen(
                    book = openBook,
                    progress = bookProgress[openBook.folder],
                    playerState = playerState,
                    onBack = { openBookFolder = null },
                    onResume = { viewModel.playBook(openBook) },
                    onChapterClick = { viewModel.playBook(openBook, it) },
                    bottomBar = { miniPlayer(true) },
                )
            }

            openPlaylist != null -> {
                val stats by viewModel.trackStats.stats.collectAsStateWithLifecycle()
                BackHandler { openPlaylistPath = null }
                PlaylistScreen(
                    playlist = openPlaylist,
                    stats = stats,
                    currentTrackId = playerState.trackId.takeIf { playerState.bookFolder == null },
                    onBack = { openPlaylistPath = null },
                    onShuffle = { viewModel.playPlaylist(openPlaylist, shuffle = true) },
                    onPlayInOrder = { viewModel.playPlaylist(openPlaylist, shuffle = false) },
                    onTrackClick = { viewModel.playPlaylist(openPlaylist, startIndex = it) },
                    bottomBar = { miniPlayer(true) },
                )
            }

            playlistsOpen && playlists.isNotEmpty() -> {
                BackHandler { playlistsOpen = false }
                PlaylistsScreen(
                    playlists = playlists,
                    onOpen = { openPlaylistPath = it.path },
                    onBack = { playlistsOpen = false },
                    bottomBar = { miniPlayer(true) },
                )
            }

            else -> HomeScreen(
                viewModel = viewModel,
                section = section,
                playlistCount = playlists.size,
                onOpenPlaylists = { playlistsOpen = true },
                onSectionChange = {
                    section = it
                    viewModel.settings.lastSection = it
                },
                onChooseFolders = { pickerSection = section },
                onOpenBook = { openBookFolder = it.folder },
                onPlayOrPauseBook = ::playOrPause,
                miniPlayer = { miniPlayer(false) },
            )
        }

        AnimatedVisibility(
            visible = showNowPlaying,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            BackHandler { nowPlayingOpen = false }
            val boosts by viewModel.settings.boosts.collectAsStateWithLifecycle()
            NowPlayingScreen(
                state = playerState,
                player = viewModel.player,
                onClose = { nowPlayingOpen = false },
                onToggleShuffle = viewModel::toggleShuffle,
                onCycleRepeat = viewModel::cycleRepeatMode,
                onSpeedChange = viewModel::setBookSpeed,
                boostDb = boosts[Settings.boostKey(playerState.bookFolder)] ?: 0,
                onBoostChange = { viewModel.settings.setBoost(playerState.bookFolder, it) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    viewModel: MainViewModel,
    section: Section,
    playlistCount: Int,
    onOpenPlaylists: () -> Unit,
    onSectionChange: (Section) -> Unit,
    onChooseFolders: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onPlayOrPauseBook: (Book) -> Unit,
    miniPlayer: @Composable () -> Unit,
) {
    val hasPermission by viewModel.hasPermission.collectAsStateWithLifecycle()
    val musicTracks by viewModel.musicTracks.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val playerState by viewModel.player.state.collectAsStateWithLifecycle()

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val searchActive = searching && ((section == Section.MUSIC && hasPermission) || section == Section.RADIO)
    val closeSearch = {
        searching = false
        query = ""
    }
    BackHandler(enabled = searchActive, onBack = closeSearch)

    val videoLocation by viewModel.video.location.collectAsStateWithLifecycle()
    val servers by viewModel.video.settings.servers.collectAsStateWithLifecycle()
    val insideVideoFolder = section == Section.VIDEO && videoLocation != VideoLocation.Home
    BackHandler(enabled = insideVideoFolder) { viewModel.video.up() }

    // Not null while a new network folder is being added; holds the address to start with.
    var newServerAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var editedServer by remember { mutableStateOf<SmbServer?>(null) }
    if (newServerAddress != null || editedServer != null) {
        val edited = editedServer
        ServerDialog(
            server = edited,
            initialAddress = newServerAddress.orEmpty(),
            onSave = { name, address, user, password ->
                viewModel.video.saveServer(edited, name, address, user, password)
            },
            onRemove = { edited?.let(viewModel.video::removeServer) },
            onDismiss = {
                newServerAddress = null
                editedServer = null
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        SearchField(
                            query = query,
                            hint = stringResource(
                                if (section == Section.RADIO) R.string.radio_search_hint else R.string.search_hint,
                            ),
                            onQueryChange = { query = it },
                        )
                    } else {
                        Text(
                            text = when (val location = videoLocation.takeIf { section == Section.VIDEO }) {
                                null -> stringResource(
                                    when (section) {
                                        Section.MUSIC -> R.string.tab_music
                                        Section.RADIO -> R.string.tab_radio
                                        else -> R.string.tab_audiobooks
                                    },
                                )
                                VideoLocation.Home -> stringResource(R.string.tab_video)
                                is VideoLocation.Device -> location.folder.substringAfterLast('/')
                                is VideoLocation.Network -> location.path.lastOrNull()
                                    ?: servers.firstOrNull { it.id == location.serverId }?.title.orEmpty()
                                is VideoLocation.Stream -> location.path.lastOrNull()?.title ?: location.server.name
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    if (insideVideoFolder) {
                        IconButton(onClick = { viewModel.video.up() }, modifier = Modifier.focusFrame(CircleShape)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    if (searchActive) {
                        IconButton(onClick = closeSearch, modifier = Modifier.focusFrame(CircleShape)) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.action_close))
                        }
                    } else if (section == Section.VIDEO) {
                        when (videoLocation) {
                            VideoLocation.Home -> IconButton(
                                onClick = { newServerAddress = "" },
                                modifier = Modifier.focusFrame(CircleShape),
                            ) {
                                Icon(Icons.Rounded.Add, stringResource(R.string.video_add_network))
                            }
                            is VideoLocation.Network, is VideoLocation.Stream ->
                                IconButton(onClick = viewModel.video::reload, modifier = Modifier.focusFrame(CircleShape)) {
                                    Icon(Icons.Rounded.Refresh, stringResource(R.string.action_refresh))
                                }
                            is VideoLocation.Device -> Unit
                        }
                    } else if (section == Section.RADIO) {
                        IconButton(onClick = { searching = true }, modifier = Modifier.focusFrame(CircleShape)) {
                            Icon(Icons.Rounded.Search, stringResource(R.string.action_search))
                        }
                    } else if (hasPermission) {
                        if (section == Section.MUSIC) {
                            IconButton(onClick = { searching = true }, modifier = Modifier.focusFrame(CircleShape)) {
                                Icon(Icons.Rounded.Search, stringResource(R.string.action_search))
                            }
                        }
                        IconButton(onClick = onChooseFolders, modifier = Modifier.focusFrame(CircleShape)) {
                            Icon(Icons.Rounded.FolderOpen, stringResource(R.string.action_choose_folders))
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                miniPlayer()
                NavigationBar {
                    NavigationBarItem(
                        modifier = Modifier.focusFrame(),
                        selected = section == Section.MUSIC,
                        onClick = { onSectionChange(Section.MUSIC) },
                        icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_music)) },
                    )
                    NavigationBarItem(
                        modifier = Modifier.focusFrame(),
                        selected = section == Section.BOOKS,
                        onClick = { onSectionChange(Section.BOOKS) },
                        icon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_audiobooks)) },
                    )
                    NavigationBarItem(
                        modifier = Modifier.focusFrame(),
                        selected = section == Section.VIDEO,
                        onClick = { onSectionChange(Section.VIDEO) },
                        icon = { Icon(Icons.Rounded.Movie, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_video)) },
                    )
                    NavigationBarItem(
                        modifier = Modifier.focusFrame(),
                        selected = section == Section.RADIO,
                        onClick = { onSectionChange(Section.RADIO) },
                        icon = { Icon(Icons.Rounded.Radio, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_radio)) },
                    )
                }
            }
        },
    ) { padding ->
        // On a TV, focus starts in the content of a section, not in the navigation bar. The
        // video section puts it on a particular line itself.
        val contentFocus = remember { FocusRequester() }
        if (section != Section.VIDEO) FocusOnShow(key = section, requester = contentFocus)
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .focusRequester(contentFocus)
                .focusGroup(),
        ) {
            val tracks = musicTracks
            val bookList = books
            when {
                // Video has its own sources and permissions; it does not need the audio library.
                section == Section.VIDEO -> VideoScreen(
                    browser = viewModel.video,
                    onAddServer = { newServerAddress = it },
                    onEditServer = { editedServer = it },
                )

                // Radio comes from the internet; it does not need the audio library either.
                section == Section.RADIO -> {
                    val stations by viewModel.radioStations.collectAsStateWithLifecycle()
                    val favorites by viewModel.radioFavorites.collectAsStateWithLifecycle()
                    RadioScreen(
                        stations = stations,
                        favorites = favorites,
                        query = if (searchActive) query else "",
                        playerState = playerState,
                        onPlay = viewModel::playRadio,
                        onToggleFavorite = viewModel::toggleRadioFavorite,
                    )
                }

                !hasPermission -> PermissionPrompt(onChanged = viewModel::refresh)

                tracks == null || bookList == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                section == Section.MUSIC -> {
                    val folders by viewModel.settings.musicFolders.collectAsStateWithLifecycle()
                    val stats by viewModel.trackStats.stats.collectAsStateWithLifecycle()
                    MusicScreen(
                        tracks = tracks,
                        stats = stats,
                        playlistCount = playlistCount,
                        hasFolders = folders.isNotEmpty(),
                        query = if (searchActive) query else "",
                        currentTrackId = playerState.trackId.takeIf { playerState.bookFolder == null },
                        onChooseFolders = onChooseFolders,
                        onShuffle = { viewModel.playMusic(shuffle = true) },
                        onPlayInOrder = { viewModel.playMusic(shuffle = false) },
                        onOpenPlaylists = onOpenPlaylists,
                        onTrackClick = { viewModel.playMusic(startTrackId = it.id) },
                    )
                }

                else -> {
                    val progress by viewModel.settings.bookProgress.collectAsStateWithLifecycle()
                    AudiobooksScreen(
                        books = bookList,
                        progress = progress,
                        playerState = playerState,
                        onChooseFolders = onChooseFolders,
                        onOpenBook = onOpenBook,
                        onPlayPause = onPlayOrPauseBook,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, hint: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(hint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
    )
}

/** Asks for access to audio files; falls back to the system settings once Android stops asking. */
@Composable
private fun PermissionPrompt(onChanged: () -> Unit) {
    val activity = LocalActivity.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        onChanged()
        val denied = result.filterValues { !it }.keys
        val audioPermission = MediaLibrary.permissionsToRequest.first()
        if (activity != null && audioPermission in denied && !activity.canAskAgain(audioPermission)) {
            activity.startActivity(
                Intent(
                    SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null),
                ),
            )
        }
    }
    EmptyState(
        icon = Icons.Rounded.Lock,
        title = stringResource(R.string.permission_title),
        text = stringResource(R.string.permission_text),
        buttonText = stringResource(R.string.permission_button),
        onButtonClick = { launcher.launch(MediaLibrary.permissionsToRequest) },
    )
}

private fun Activity.canAskAgain(permission: String): Boolean =
    ActivityCompat.shouldShowRequestPermissionRationale(this, permission)

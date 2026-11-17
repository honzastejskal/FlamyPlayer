package io.github.flamy69.flamyplayer.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as SystemSettings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.flamy69.flamyplayer.R
import io.github.flamy69.flamyplayer.data.VideoFolder
import io.github.flamy69.flamyplayer.data.VideoLibrary
import io.github.flamy69.flamyplayer.network.SmbException
import io.github.flamy69.flamyplayer.network.SmbServer
import io.github.flamy69.flamyplayer.video.VideoPlayerViewModel
import java.util.Locale

/** The video section: an overview of sources, and the folders below it. */
@Composable
fun VideoScreen(browser: VideoBrowser, onAddServer: (address: String) -> Unit, onEditServer: (SmbServer) -> Unit) {
    val location by browser.location.collectAsStateWithLifecycle()
    // The videos of the device are only looked up once somebody actually comes here.
    LaunchedEffect(Unit) { browser.refreshDevice(force = true) }

    when (val current = location) {
        VideoLocation.Home -> VideoHome(browser, onAddServer, onEditServer)
        is VideoLocation.Device -> DeviceFolder(browser, current.folder)
        is VideoLocation.Network -> NetworkFolder(browser, current)
        is VideoLocation.Stream -> StreamFolder(browser, current)
    }
}

@Composable
private fun VideoHome(
    browser: VideoBrowser,
    onAddServer: (address: String) -> Unit,
    onEditServer: (SmbServer) -> Unit,
) {
    val servers by browser.settings.servers.collectAsStateWithLifecycle()
    val discovery by browser.discovery.collectAsStateWithLifecycle()
    val hasPermission by browser.hasPermission.collectAsStateWithLifecycle()
    val folders by browser.folders.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Android 17 asks the user before an app may talk to devices on the local network.
    var pendingServer by remember { mutableStateOf<SmbServer?>(null) }
    val askForNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Open the folder either way; without the permission it explains what is missing.
        pendingServer?.let(browser::openServer)
        pendingServer = null
    }
    val openServer = { server: SmbServer ->
        val permission = LocalNetwork.permission
        if (permission != null && !LocalNetwork.isAllowed(context)) {
            pendingServer = server
            askForNetwork.launch(permission)
        } else {
            browser.openServer(server)
        }
    }
    // Computers and NAS boxes on the Wi-Fi are looked up once, as soon as that is allowed.
    LaunchedEffect(Unit) {
        if (LocalNetwork.isAllowed(context)) browser.discover()
    }
    val searchNetwork = rememberPermissionRequest(
        permission = LocalNetwork.permission,
        isSatisfied = { LocalNetwork.isAllowed(context) },
        onResult = { if (LocalNetwork.isAllowed(context)) browser.discover(again = true) },
    )
    val askForVideos = rememberPermissionRequest(
        permission = VideoLibrary.permission,
        isSatisfied = { VideoLibrary.hasPermission(context) },
        onResult = { browser.refreshDevice(force = true) },
    )

    // On a TV: back on the line that was left, or on the first network location.
    val focusKey = remember {
        browser.cameFrom ?: servers.firstOrNull()?.let { browser.serverKey(it.id) } ?: ADD_NETWORK_KEY
    }
    val focusRequester = remember { FocusRequester() }
    FocusOnShow(key = Unit, requester = focusRequester)
    val focusHere = { key: String -> if (key == focusKey) Modifier.focusRequester(focusRequester) else Modifier }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "network-header", contentType = "header") {
            SectionHeader(stringResource(R.string.video_section_network)) {
                if (discovery == Discovery.Searching) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .padding(14.dp)
                            .size(20.dp),
                    )
                } else {
                    IconButton(onClick = searchNetwork, modifier = Modifier.focusFrame(CircleShape)) {
                        Icon(
                            Icons.Rounded.Refresh,
                            stringResource(R.string.video_find_devices),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        items(servers, key = { browser.serverKey(it.id) }, contentType = { "server" }) { server ->
            BrowserRow(
                icon = Icons.Rounded.Lan,
                title = server.title,
                subtitle = if (server.name.isBlank()) "" else server.address,
                onClick = { openServer(server) },
                modifier = focusHere(browser.serverKey(server.id)),
                trailing = {
                    IconButton(onClick = { onEditServer(server) }, modifier = Modifier.focusFrame(CircleShape)) {
                        Icon(
                            Icons.Rounded.Edit,
                            stringResource(R.string.action_edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
        when (val found = discovery) {
            Discovery.Idle -> item(key = "found-search", contentType = "add") {
                BrowserRow(
                    icon = Icons.Rounded.Search,
                    title = stringResource(R.string.video_find_devices),
                    subtitle = "",
                    onClick = searchNetwork,
                )
            }

            Discovery.Searching -> item(key = "found-searching", contentType = "hint") {
                Hint(stringResource(R.string.video_found_searching))
            }

            Discovery.NoWifi -> item(key = "found-no-wifi", contentType = "hint") {
                Hint(stringResource(R.string.video_found_no_wifi))
            }

            is Discovery.Done -> if (found.devices.isEmpty() && found.mediaServers.isEmpty()) {
                item(key = "found-none", contentType = "hint") {
                    Hint(stringResource(R.string.video_found_none))
                }
            } else {
                // Media servers need no sign-in and nothing to be saved: tap and browse.
                items(found.mediaServers, key = { browser.mediaServerKey(it) }, contentType = { "stream" }) { server ->
                    BrowserRow(
                        icon = Icons.Rounded.VideoLibrary,
                        title = server.name,
                        subtitle = stringResource(R.string.video_media_server) + " · " + server.address,
                        onClick = { browser.openMediaServer(server) },
                        modifier = focusHere(browser.mediaServerKey(server)),
                    )
                }
                // A device that was saved as a whole is listed above already. One that only has
                // single folders saved stays here, for adding more of its folders.
                val fresh = found.devices.filter { device ->
                    servers.none { it.root.isEmpty() && device.isCalled(it.host) }
                }
                items(fresh, key = { "found:" + it.address }, contentType = { "found" }) { device ->
                    BrowserRow(
                        icon = Icons.Rounded.Dns,
                        title = device.host,
                        subtitle = if (device.name != null) device.address else "",
                        onClick = { onAddServer(device.host) },
                        trailing = {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        },
                    )
                }
            }
        }
        item(key = ADD_NETWORK_KEY, contentType = "add") {
            BrowserRow(
                icon = Icons.Rounded.Add,
                title = stringResource(R.string.video_add_network),
                subtitle = stringResource(R.string.video_add_network_hint),
                onClick = { onAddServer("") },
                modifier = focusHere(ADD_NETWORK_KEY),
            )
        }

        item(key = "device-header", contentType = "header") {
            SectionHeader(stringResource(R.string.video_section_device))
        }
        val deviceFolders = folders
        when {
            !hasPermission -> item(key = "device-permission") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.video_permission_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(12.dp))
                    FilledTonalButton(onClick = askForVideos, modifier = Modifier.focusFrame(RoundedCornerShape(50))) {
                        Text(stringResource(R.string.permission_button))
                    }
                }
            }

            deviceFolders == null -> item(key = "device-loading") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            deviceFolders.isEmpty() -> item(key = "device-empty") {
                Text(
                    text = stringResource(R.string.video_none_on_device),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            else -> items(deviceFolders, key = { browser.deviceFolderKey(it.path) }, contentType = { "folder" }) { folder ->
                BrowserRow(
                    icon = Icons.Rounded.Folder,
                    title = folder.name,
                    subtitle = pluralStringResource(R.plurals.video_count, folder.videos.size, folder.videos.size),
                    onClick = { browser.openFolder(folder) },
                    modifier = focusHere(browser.deviceFolderKey(folder.path)),
                )
            }
        }
    }
}

@Composable
private fun DeviceFolder(browser: VideoBrowser, path: String) {
    val folders by browser.folders.collectAsStateWithLifecycle()
    val positions by browser.settings.positions.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val loaded = folders
    val folder: VideoFolder? = loaded?.firstOrNull { it.path == path }
    when {
        loaded == null -> Loading()

        folder == null -> EmptyState(
            icon = Icons.Rounded.VideoLibrary,
            title = stringResource(R.string.video_folder_empty_title),
            text = stringResource(R.string.video_folder_empty_text),
        )

        else -> FocusedList(key = folder, focusIndex = 0) { focusAt ->
            itemsIndexed(folder.videos, key = { _, video -> video.id }, contentType = { _, _ -> "video" }) { index, video ->
                BrowserRow(
                    modifier = focusAt(index),
                    icon = Icons.Rounded.Movie,
                    title = video.fileName,
                    subtitle = listOfNotNull(
                        formatTime(video.durationMs).takeIf { video.durationMs > 0 },
                        formatSize(video.sizeBytes).takeIf { video.sizeBytes > 0 },
                    ).joinToString(" · "),
                    resumeAtMs = positions[video.path],
                    onClick = { browser.playDeviceVideo(context, folder, video) },
                )
            }
        }
    }
}

@Composable
private fun NetworkFolder(browser: VideoBrowser, location: VideoLocation.Network) {
    val listing by browser.listing.collectAsStateWithLifecycle()
    val positions by browser.settings.positions.collectAsStateWithLifecycle()
    val servers by browser.settings.servers.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val server = servers.firstOrNull { it.id == location.serverId }
    val directory = server?.root.orEmpty() + location.path

    when (val current = listing) {
        NetworkListing.Loading -> Loading()

        is NetworkListing.Failed -> NetworkError(
            reason = current.reason,
            detail = current.detail,
            listingShares = directory.isEmpty(),
            onRetry = browser::reload,
        )

        is NetworkListing.Ready -> {
            val visible = remember(current) {
                current.entries.filter { it.isDirectory || it.isVideo }
            }
            if (visible.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.VideoLibrary,
                    title = stringResource(R.string.video_folder_empty_title),
                    text = stringResource(R.string.video_folder_empty_text),
                )
            } else {
                val cameFrom = remember(current) { browser.cameFrom }
                val focusIndex = visible.indexOfFirst { it.name == cameFrom }.coerceAtLeast(0)
                FocusedList(key = current, focusIndex = focusIndex) { focusAt ->
                    itemsIndexed(visible, key = { _, entry -> entry.name }, contentType = { _, entry -> entry.isDirectory }) { index, entry ->
                        if (entry.isDirectory) {
                            BrowserRow(
                                icon = Icons.Rounded.Folder,
                                title = entry.name,
                                subtitle = "",
                                onClick = { browser.openDirectory(entry.name) },
                                modifier = focusAt(index),
                            )
                        } else {
                            BrowserRow(
                                icon = Icons.Rounded.Movie,
                                title = entry.name,
                                subtitle = formatSize(entry.size),
                                resumeAtMs = positions[browser.networkKey(location.serverId, directory, entry.name)],
                                onClick = { browser.playNetworkVideo(context, entry) },
                                modifier = focusAt(index),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamFolder(browser: VideoBrowser, location: VideoLocation.Stream) {
    val listing by browser.streamListing.collectAsStateWithLifecycle()
    val positions by browser.settings.positions.collectAsStateWithLifecycle()
    val context = LocalContext.current

    when (val current = listing) {
        StreamListing.Loading -> Loading()

        is StreamListing.Failed -> EmptyState(
            icon = Icons.Rounded.CloudOff,
            title = stringResource(R.string.network_error_title),
            text = stringResource(R.string.stream_error),
            buttonText = stringResource(R.string.action_retry),
            onButtonClick = browser::reload,
            detail = current.detail,
        )

        is StreamListing.Ready -> if (current.entries.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.VideoLibrary,
                title = stringResource(R.string.video_folder_empty_title),
                text = stringResource(R.string.video_folder_empty_text),
            )
        } else {
            val cameFrom = remember(current) { browser.cameFrom }
            val focusIndex = current.entries.indexOfFirst { it.isFolder && it.id == cameFrom }.coerceAtLeast(0)
            FocusedList(key = current, focusIndex = focusIndex) { focusAt ->
                // By position: a server may hand out the same item more than once.
                items(current.entries.size, contentType = { current.entries[it].isFolder }) { index ->
                    val entry = current.entries[index]
                    if (entry.isFolder) {
                        BrowserRow(
                            icon = Icons.Rounded.Folder,
                            title = entry.title,
                            subtitle = "",
                            onClick = { browser.openStreamFolder(entry) },
                            modifier = focusAt(index),
                        )
                    } else {
                        BrowserRow(
                            icon = Icons.Rounded.Movie,
                            title = entry.title,
                            subtitle = listOfNotNull(
                                formatTime(entry.durationMs).takeIf { entry.durationMs > 0 },
                                formatSize(entry.sizeBytes).takeIf { entry.sizeBytes > 0 },
                            ).joinToString(" · "),
                            resumeAtMs = positions[browser.streamKey(location.server, entry)],
                            onClick = { browser.playStreamVideo(context, entry) },
                            modifier = focusAt(index),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkError(reason: SmbException.Reason, detail: String, listingShares: Boolean, onRetry: () -> Unit) {
    val context = LocalContext.current
    val permissionMissing = reason == SmbException.Reason.UNREACHABLE && !LocalNetwork.isAllowed(context)
    val askForNetwork = rememberPermissionRequest(
        permission = LocalNetwork.permission,
        isSatisfied = { LocalNetwork.isAllowed(context) },
        onResult = onRetry,
    )

    val text = when {
        permissionMissing -> R.string.network_permission_text
        // Some servers do not hand out the list of their shared folders to everybody.
        listingShares && (reason == SmbException.Reason.OTHER || reason == SmbException.Reason.ACCESS_DENIED) ->
            R.string.network_error_shares
        else -> VideoPlayerViewModel.networkErrorText(reason)
    }
    EmptyState(
        icon = Icons.Rounded.CloudOff,
        title = stringResource(R.string.network_error_title),
        text = stringResource(text),
        buttonText = stringResource(if (permissionMissing) R.string.permission_button else R.string.action_retry),
        onButtonClick = if (permissionMissing) askForNetwork else onRetry,
        // The technical reason, small, for when the explanation above does not help.
        detail = detail.takeUnless { permissionMissing },
    )
}

/** Dialog for adding a network location, or for editing or removing an existing one. */
@Composable
fun ServerDialog(
    server: SmbServer?,
    initialAddress: String,
    onSave: (name: String, address: String, user: String, password: String) -> Boolean,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var address by rememberSaveable { mutableStateOf(server?.address ?: initialAddress) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(server?.name.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(server?.user.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var addressInvalid by rememberSaveable { mutableStateOf(false) }

    val nameFocus = remember { FocusRequester() }
    val plainText = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        imeAction = ImeAction.Next,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (server == null) R.string.network_title_add else R.string.network_title_edit))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        addressInvalid = false
                    },
                    label = { Text(stringResource(R.string.network_address)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (addressInvalid) R.string.network_address_error else R.string.network_address_hint,
                            ),
                        )
                    },
                    isError = addressInvalid,
                    singleLine = true,
                    keyboardOptions = plainText.copy(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .leaveWithUpDown(),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.network_user)) },
                    supportingText = { Text(stringResource(R.string.network_user_hint)) },
                    singleLine = true,
                    keyboardOptions = plainText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .leaveWithUpDown(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.network_password)) },
                    placeholder = {
                        if (!server?.secret.isNullOrEmpty()) Text(stringResource(R.string.network_password_unchanged))
                    },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.cd_hide_password else R.string.cd_show_password,
                                ),
                            )
                        }
                    },
                    keyboardOptions = plainText.copy(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .leaveWithUpDown()
                        // "Next" on the keyboard goes on to the name, not to the eye button.
                        .focusProperties { next = nameFocus },
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.network_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .leaveWithUpDown()
                        .focusRequester(nameFocus),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (onSave(name, address, user, password)) onDismiss() else addressInvalid = true
                },
                modifier = Modifier.focusFrame(RoundedCornerShape(50)),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (server != null) {
                    TextButton(
                        onClick = {
                            onRemove()
                            onDismiss()
                        },
                        modifier = Modifier.focusFrame(RoundedCornerShape(50)),
                    ) {
                        Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.focusFrame(RoundedCornerShape(50))) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

/**
 * Asks for a runtime permission and calls [onResult] whatever the user decided. Once Android
 * no longer shows its dialog, the app's page in the system settings opens instead.
 *
 * @param isSatisfied whether the app has what it needs; not always the same as "granted",
 * because the user may also allow access to selected videos only
 */
@Composable
private fun rememberPermissionRequest(
    permission: String?,
    isSatisfied: () -> Boolean,
    onResult: () -> Unit,
): () -> Unit {
    val activity = LocalActivity.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onResult()
        if (!isSatisfied() && activity != null && permission != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        ) {
            activity.startActivity(
                Intent(
                    SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null),
                ),
            )
        }
    }
    return { if (permission != null) launcher.launch(permission) else onResult() }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SectionHeader(text: String, action: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        action()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The key of the line for adding a network location. */
private const val ADD_NETWORK_KEY = "network-add"

/**
 * The list of a folder. On a TV it takes focus as soon as it is shown, on the line
 * [focusIndex]; [content] gets the modifier for each line that ties the right one to the focus.
 */
@Composable
private fun FocusedList(key: Any, focusIndex: Int, content: LazyListScope.(focusAt: (index: Int) -> Modifier) -> Unit) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    FocusOnShow(key = key, requester = focusRequester, listState = listState, index = focusIndex)
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        content { index -> if (index == focusIndex) Modifier.focusRequester(focusRequester) else Modifier }
    }
}

/** One line of the video browser: a source, a folder or a video. */
@Composable
private fun BrowserRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    resumeAtMs: Long? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusFrame()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (resumeAtMs != null) {
                Text(
                    text = stringResource(R.string.video_resume_at, formatTime(resumeAtMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        trailing()
    }
}

/** `1.4 GB` or `730 MB`. */
private fun formatSize(bytes: Long): String {
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 1024) {
        String.format(Locale.getDefault(), "%.1f GB", megabytes / 1024)
    } else {
        String.format(Locale.getDefault(), "%.0f MB", megabytes)
    }
}

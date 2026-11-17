package io.github.flamy69.flamyplayer.ui

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.flamy69.flamyplayer.R
import io.github.flamy69.flamyplayer.data.FolderNode
import io.github.flamy69.flamyplayer.data.FolderSelection
import io.github.flamy69.flamyplayer.data.Section

/**
 * Browser over the folders that contain audio. Ticking a folder selects it together with
 * everything below it; the change applies immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(
    section: Section,
    root: FolderNode?,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit,
) {
    var currentPath by rememberSaveable { mutableStateOf<String?>(null) }
    // The nodes from the root down to the folder being shown.
    val chain = remember(root, currentPath) {
        currentPath?.let { root?.chainTo(it) }.orEmpty().ifEmpty { listOfNotNull(root) }
    }
    val node = chain.lastOrNull()
    val atRoot = chain.size <= 1
    val goUp = { currentPath = chain[chain.size - 2].path }

    BackHandler(enabled = !atRoot, onBack = goUp)

    val internalStorage = remember { Environment.getExternalStorageDirectory().path }
    val internalStorageLabel = stringResource(R.string.picker_internal_storage)
    val allStorageLabel = stringResource(R.string.picker_all_storage)
    fun readablePath(path: String): String = when {
        path == internalStorage -> internalStorageLabel
        path.startsWith("$internalStorage/") -> internalStorageLabel + path.removePrefix(internalStorage)
        path.isEmpty() || path == "/storage" -> allStorageLabel
        else -> path
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(
                                if (section == Section.MUSIC) R.string.picker_title_music else R.string.picker_title_books,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (node != null) {
                            Text(
                                text = readablePath(node.path),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (atRoot) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.action_close))
                        }
                    } else {
                        IconButton(onClick = goUp) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = onClearAll) { Text(stringResource(R.string.action_clear_all)) }
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Check, stringResource(R.string.action_done))
                    }
                },
            )
        },
    ) { padding ->
        // Selections that are not a folder of the tree any more, typically because their files are gone.
        val orphans = remember(root, selected) {
            selected.filter { root == null || root.chainTo(it).isEmpty() }.sorted()
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item(key = "hint", contentType = "hint") {
                Text(
                    text = stringResource(
                        if (section == Section.MUSIC) R.string.picker_hint_music else R.string.picker_hint_books,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (node == null) {
                item(key = "empty", contentType = "hint") {
                    Text(
                        text = stringResource(R.string.picker_no_audio),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                item(key = "self", contentType = "folder") {
                    FolderRow(
                        name = stringResource(R.string.picker_this_folder),
                        trackCount = node.totalCount,
                        checked = FolderSelection.covers(selected, node.path),
                        enabled = !FolderSelection.coveredByAncestor(selected, node.path),
                        hasChildren = false,
                        emphasized = true,
                        onToggle = { onToggle(node.path) },
                        onOpen = { onToggle(node.path) },
                    )
                    HorizontalDivider()
                }
                items(node.children, key = { it.path }, contentType = { "folder" }) { child ->
                    val enabled = !FolderSelection.coveredByAncestor(selected, child.path)
                    // Seen from above the internal storage, "emulated/0/Music" reads better by its name.
                    val insideInternal = child.path == internalStorage || child.path.startsWith("$internalStorage/")
                    FolderRow(
                        name = if (insideInternal && !node.path.startsWith(internalStorage)) {
                            readablePath(child.path)
                        } else {
                            child.name
                        },
                        trackCount = child.totalCount,
                        checked = FolderSelection.covers(selected, child.path),
                        enabled = enabled,
                        hasChildren = child.children.isNotEmpty(),
                        emphasized = false,
                        onToggle = { onToggle(child.path) },
                        onOpen = {
                            if (child.children.isNotEmpty()) {
                                currentPath = child.path
                            } else if (enabled) {
                                onToggle(child.path)
                            }
                        },
                    )
                }
            }

            if (atRoot && orphans.isNotEmpty()) {
                item(key = "orphans-divider", contentType = "divider") { HorizontalDivider() }
                items(orphans, key = { "orphan:$it" }, contentType = { "orphan" }) { path ->
                    OrphanRow(
                        path = readablePath(path),
                        trackCount = root?.countUnder(path) ?: 0,
                        onRemove = { onToggle(path) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    name: String,
    trackCount: Int,
    checked: Boolean,
    enabled: Boolean,
    hasChildren: Boolean,
    emphasized: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusFrame()
            .clickable(onClick = onOpen)
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.track_count, trackCount, trackCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hasChildren) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = stringResource(R.string.cd_open_folder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OrphanRow(path: String, trackCount: Int, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.FolderOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            Text(path, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (trackCount > 0) {
                    pluralStringResource(R.plurals.track_count, trackCount, trackCount)
                } else {
                    stringResource(R.string.picker_missing)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Close, stringResource(R.string.action_remove))
        }
    }
}

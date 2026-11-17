package io.github.flamy69.flamyplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.flamy69.flamyplayer.R
import io.github.flamy69.flamyplayer.data.Playlist
import io.github.flamy69.flamyplayer.data.TrackStat

/** The playlist files found in the music folders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    onOpen: (Playlist) -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.playlists_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            items(playlists, key = { it.path }, contentType = { "playlist" }) { playlist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusFrame()
                        .clickable { onOpen(playlist) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = pluralStringResource(R.plurals.track_count, playlist.tracks.size, playlist.tracks.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The tracks of one playlist, in the order the playlist names them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    playlist: Playlist,
    stats: Map<String, TrackStat>,
    currentTrackId: Long?,
    onBack: () -> Unit,
    onShuffle: () -> Unit,
    onPlayInOrder: () -> Unit,
    onTrackClick: (index: Int) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val totalDurationMs = remember(playlist) { playlist.tracks.sumOf { it.durationMs } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item(key = "header", contentType = "header") {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                    Text(
                        text = pluralStringResource(R.plurals.track_count, playlist.tracks.size, playlist.tracks.size) +
                            " · " + formatLongDuration(totalDurationMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onShuffle, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_shuffle), maxLines = 1)
                        }
                        FilledTonalButton(onClick = onPlayInOrder, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.action_play_in_order),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            // By position: a playlist may name the same track more than once.
            itemsIndexed(playlist.tracks, contentType = { _, _ -> "track" }) { index, track ->
                TrackRow(
                    title = track.title,
                    subtitle = listOfNotNull(track.artist, track.album).joinToString(" · "),
                    durationMs = track.durationMs,
                    isCurrent = track.id == currentTrackId,
                    onClick = { onTrackClick(index) },
                    detail = stats[track.path]?.let(::describeStat),
                )
            }
        }
    }
}

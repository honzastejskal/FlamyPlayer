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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.flamy69.flamyplayer.R
import io.github.flamy69.flamyplayer.data.Track
import io.github.flamy69.flamyplayer.data.TrackStat

@Composable
fun MusicScreen(
    tracks: List<Track>,
    stats: Map<String, TrackStat>,
    playlistCount: Int,
    hasFolders: Boolean,
    query: String,
    currentTrackId: Long?,
    onChooseFolders: () -> Unit,
    onShuffle: () -> Unit,
    onPlayInOrder: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onTrackClick: (Track) -> Unit,
) {
    if (tracks.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.LibraryMusic,
            title = stringResource(if (hasFolders) R.string.music_no_tracks_title else R.string.music_empty_title),
            text = stringResource(if (hasFolders) R.string.music_no_tracks_text else R.string.music_empty_text),
            buttonText = stringResource(R.string.action_choose_folders),
            onButtonClick = onChooseFolders,
        )
        return
    }

    val trimmedQuery = query.trim()
    val visible = remember(tracks, trimmedQuery) {
        if (trimmedQuery.isEmpty()) tracks else tracks.filter { it.matches(trimmedQuery) }
    }
    val totalDurationMs = remember(tracks) { tracks.sumOf { it.durationMs } }

    LazyColumn(Modifier.fillMaxSize()) {
        if (trimmedQuery.isEmpty()) {
            item(key = "header", contentType = "header") {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                    Text(
                        text = pluralStringResource(R.plurals.track_count, tracks.size, tracks.size) +
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
            // Playlist files are kept apart from the tracks, so that a track which is also
            // named in a playlist is not in the big list twice.
            if (playlistCount > 0) {
                item(key = "playlists", contentType = "playlists") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusFrame()
                            .clickable(onClick = onOpenPlaylists)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.playlists_title),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = playlistCount.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else if (visible.isEmpty()) {
            item(key = "no-results", contentType = "message") {
                Text(
                    text = stringResource(R.string.search_no_results, trimmedQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(visible, key = { it.id }, contentType = { "track" }) { track ->
            TrackRow(
                title = track.title,
                subtitle = listOfNotNull(track.artist, track.album).joinToString(" · "),
                durationMs = track.durationMs,
                isCurrent = track.id == currentTrackId,
                onClick = { onTrackClick(track) },
                detail = stats[track.path]?.let(::describeStat),
            )
        }
    }
}

/** `3× · 87 %`: how often the track came up while shuffling, and how much of it was heard last time. */
internal fun describeStat(stat: TrackStat): String? = listOfNotNull(
    stat.shufflePlays.takeIf { it > 0 }?.let { "$it×" },
    stat.lastPercent?.let { "$it %" },
).joinToString(" · ").ifEmpty { null }

/**
 * One line of a track list; also used for the chapters of a book.
 *
 * @param detail a short note shown under the duration
 */
@Composable
fun TrackRow(
    title: String,
    subtitle: String,
    durationMs: Long,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusFrame()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
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
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatTime(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

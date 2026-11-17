package io.github.flamy69.flamyplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.flamy69.flamyplayer.R
import io.github.flamy69.flamyplayer.data.RadioStation
import io.github.flamy69.flamyplayer.playback.PlayerState

/**
 * The radio section: the listener's favorite stations first, then all the others.
 *
 * @param stations all stations sorted by name; `null` while they are being loaded
 * @param onPlay called with the stations in the order they are shown, so that "next" and
 * "previous" move through the list as the listener sees it
 */
@Composable
fun RadioScreen(
    stations: List<RadioStation>?,
    favorites: Set<String>,
    query: String,
    playerState: PlayerState,
    onPlay: (shown: List<RadioStation>, station: RadioStation) -> Unit,
    onToggleFavorite: (RadioStation) -> Unit,
) {
    if (stations == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val trimmedQuery = query.trim()
    val favorite = remember(stations, favorites, trimmedQuery) {
        stations.filter { it.id in favorites && (trimmedQuery.isEmpty() || it.matches(trimmedQuery)) }
    }
    val others = remember(stations, favorites, trimmedQuery) {
        stations.filter { it.id !in favorites && (trimmedQuery.isEmpty() || it.matches(trimmedQuery)) }
    }
    val shown = remember(favorite, others) { favorite + others }

    LazyColumn(Modifier.fillMaxSize()) {
        if (shown.isEmpty()) {
            item(key = "no-results", contentType = "message") {
                Text(
                    text = stringResource(R.string.search_no_results, trimmedQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (favorite.isNotEmpty()) {
            item(key = "favorites-header", contentType = "header") { Header(stringResource(R.string.radio_favorites)) }
        }
        items(favorite, key = { it.id }, contentType = { "station" }) { station ->
            StationRow(station, isFavorite = true, playerState, { onPlay(shown, station) }, { onToggleFavorite(station) })
        }
        if (others.isNotEmpty()) {
            item(key = "all-header", contentType = "header") { Header(stringResource(R.string.radio_all)) }
        }
        items(others, key = { it.id }, contentType = { "station" }) { station ->
            StationRow(station, isFavorite = false, playerState, { onPlay(shown, station) }, { onToggleFavorite(station) })
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun StationRow(
    station: RadioStation,
    isFavorite: Boolean,
    playerState: PlayerState,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val isCurrent = playerState.mediaId == station.mediaId
    val failed = isCurrent && playerState.failed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusFrame()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Radio,
            contentDescription = null,
            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = station.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val note = when {
                failed -> stringResource(R.string.radio_failed)
                // What is on air right now, if the station tells.
                isCurrent && playerState.title.isNotBlank() && playerState.title != station.name -> playerState.title
                else -> station.description
            }
            if (note.isNotEmpty()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleFavorite, modifier = Modifier.focusFrame(CircleShape)) {
            Icon(
                imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = stringResource(
                    if (isFavorite) R.string.cd_favorite_remove else R.string.cd_favorite_add,
                ),
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

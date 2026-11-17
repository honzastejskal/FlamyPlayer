package io.github.flamy69.flamyplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.flamy69.flamyplayer.R
import io.github.flamy69.flamyplayer.data.Book
import io.github.flamy69.flamyplayer.data.BookProgress
import io.github.flamy69.flamyplayer.playback.PlayerState
import kotlin.math.roundToInt

@Composable
fun AudiobooksScreen(
    books: List<Book>,
    progress: Map<String, BookProgress>,
    playerState: PlayerState,
    onChooseFolders: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onPlayPause: (Book) -> Unit,
) {
    if (books.isEmpty()) {
        EmptyState(
            icon = Icons.AutoMirrored.Rounded.MenuBook,
            title = stringResource(R.string.books_empty_title),
            text = stringResource(R.string.books_empty_text),
            buttonText = stringResource(R.string.action_choose_folders),
            onButtonClick = onChooseFolders,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(books, key = { it.folder }) { book ->
            BookCard(
                book = book,
                progress = progress[book.folder],
                isPlaying = playerState.isPlaying && playerState.bookFolder == book.folder,
                onOpen = { onOpenBook(book) },
                onPlayPause = { onPlayPause(book) },
            )
        }
    }
}

@Composable
private fun BookCard(
    book: Book,
    progress: BookProgress?,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .focusFrame(RoundedCornerShape(16.dp)),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = bookSummary(book),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (book.tracks.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    ProgressBar(book.fraction(progress))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = bookProgressText(book, progress),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (book.tracks.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                FilledIconButton(onClick = onPlayPause) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.cd_pause else R.string.cd_play),
                    )
                }
            }
        }
    }
}

@Composable
private fun bookSummary(book: Book): String =
    if (book.tracks.isEmpty()) {
        stringResource(R.string.book_no_files)
    } else {
        pluralStringResource(R.plurals.chapter_count, book.tracks.size, book.tracks.size) +
            " · " + formatLongDuration(book.durationMs)
    }

@Composable
private fun bookProgressText(book: Book, progress: BookProgress?): String =
    if (progress == null) {
        stringResource(R.string.book_not_started)
    } else {
        stringResource(
            R.string.book_progress,
            book.resumePoint(progress).first + 1,
            book.tracks.size,
            (book.fraction(progress) * 100).roundToInt(),
        )
    }

/** The chapters of one book. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    book: Book,
    progress: BookProgress?,
    playerState: PlayerState,
    onBack: () -> Unit,
    onResume: () -> Unit,
    onChapterClick: (Int) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val isLoaded = playerState.bookFolder == book.folder
    val currentIndex = if (isLoaded) {
        book.tracks.indexOfFirst { it.id == playerState.trackId }
    } else if (progress != null) {
        book.resumePoint(progress).first
    } else {
        -1
    }
    // Open the list at the chapter the listener is in.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item(key = "header", contentType = "header") {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                    Text(
                        text = bookSummary(book),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (book.tracks.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        ProgressBar(book.fraction(progress))
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (progress != null) R.string.book_resume else R.string.book_start))
                        }
                    }
                }
            }
            itemsIndexed(book.tracks, key = { _, track -> track.id }, contentType = { _, _ -> "track" }) { index, track ->
                TrackRow(
                    title = track.displayName,
                    subtitle = "",
                    durationMs = track.durationMs,
                    isCurrent = index == currentIndex,
                    onClick = { onChapterClick(index) },
                )
            }
        }
    }
}

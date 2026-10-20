package com.keyspaceapps.goodtv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.ui.browse.PosterCard

private const val PLACEHOLDER_COUNT = 8

private data class HomeRow(val titleRes: Int, val items: List<String>)

private fun placeholders(prefix: String) = (1..PLACEHOLDER_COUNT).map { "$prefix $it" }

// TODO: hook up each row (watch progress, popular movies, popular series) and the search bar.
private val rows = listOf(
    HomeRow(R.string.home_continue_watching, placeholders("Continue")),
    HomeRow(R.string.home_popular_movies, placeholders("Movie")),
    HomeRow(R.string.home_popular_shows, placeholders("Show")),
)

/**
 * The Home tab: a search bar over Continue Watching, Popular Movies and Popular Shows rails.
 * Layout only for now; the rows hold placeholder tiles and search does nothing yet.
 * Initial focus goes to the first Continue Watching tile, so UP reaches search, then the tabs.
 */
@Composable
fun HomeTabScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    val firstItem = remember { FocusRequester() }
    var requested by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
        item(key = "search") {
            HomeSearchBar(
                query = query,
                onQueryChange = { query = it },
                hint = stringResource(R.string.home_search_hint),
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
            )
        }
        itemsIndexed(rows, key = { _, r -> r.titleRes }) { rowIndex, row ->
            Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(
                    stringResource(row.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(row.items, key = { _, title -> title }) { index, title ->
                        val focus = if (rowIndex == 0 && index == 0) {
                            Modifier.focusRequester(firstItem).onPlaced {
                                if (!requested) { requested = true; firstItem.requestFocus() }
                            }
                        } else {
                            Modifier
                        }
                        PosterCard(title = title, imageUrl = null, onClick = {}, modifier = focus)
                    }
                }
            }
        }
    }
}

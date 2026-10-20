package com.keyspaceapps.goodtv.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.ui.browse.TvDropdown

/** The All / Live / Movies / Series picker beside the search bar. */
@Composable
fun SearchScopeDropdown(scope: SearchScope, onScope: (SearchScope) -> Unit, modifier: Modifier = Modifier) {
    TvDropdown(
        options = SearchScope.entries,
        selected = scope,
        label = { stringResource(it.labelRes) },
        onSelect = onScope,
        modifier = modifier,
    )
}

val SearchScope.labelRes: Int
    get() = when (this) {
        SearchScope.ALL -> R.string.search_scope_all
        SearchScope.LIVE -> R.string.tab_live
        SearchScope.MOVIES -> R.string.tab_movies
        SearchScope.SERIES -> R.string.tab_series
    }

val SearchScope.hintRes: Int
    get() = when (this) {
        SearchScope.ALL -> R.string.home_search_hint
        SearchScope.LIVE -> R.string.search_hint_live
        SearchScope.MOVIES -> R.string.search_hint_movies
        SearchScope.SERIES -> R.string.search_hint_series
    }

package com.keyspaceapps.goodtv.ui.tmdb

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.data.tmdb.TmdbMeta
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.ui.browse.Formats
import kotlinx.coroutines.delay

fun ContentKind.tmdbKind(): TmdbKind? = when (this) {
    ContentKind.MOVIE -> TmdbKind.MOVIE
    ContentKind.SERIES -> TmdbKind.TV
    ContentKind.LIVE -> null
}

/** Posters wait this long on screen before asking TMDB, so a fast scroll past a row costs nothing. */
const val POSTER_SETTLE_MS = 300L

/**
 * TMDB data for one catalog title, or null while loading, when TMDB has none, or when TMDB is off.
 * An answer already known this session is returned on the first frame; otherwise the lookup runs
 * after [settleMs] and is cancelled if the caller leaves composition first.
 */
@Composable
fun rememberTmdbMeta(kind: TmdbKind?, tmdbId: Long?, title: String, settleMs: Long = 0): TmdbMeta? {
    val repo = LocalContext.current.appGraph().tmdb
    val known = if (kind == null || !repo.isEnabled) Result.success(null) else repo.peek(kind, tmdbId, title)
    val meta by produceState(initialValue = known?.getOrNull(), kind, tmdbId, title, repo) {
        if (kind == null || known != null) return@produceState
        if (settleMs > 0) delay(settleMs)
        value = repo.lookup(kind, tmdbId, title).getOrNull()
    }
    return meta
}

/**
 * The rating for a detail page's meta line: TMDB's when it has enough votes ("★ 8.2 TMDB"),
 * otherwise the provider's own ("★ 7.0"), otherwise nothing.
 */
@Composable
fun detailRating(tmdb: TmdbMeta?, providerRating: Double?): String? =
    tmdb?.reliableRating?.let { stringResource(R.string.tmdb_rating, Formats.rating(it)!!) } ?: Formats.rating(providerRating)

/**
 * TMDB's terms require its logo and notice wherever its data is shown; shown only when it is. The
 * logo stays small: the terms also require it to be less prominent than the app's own branding.
 */
@Composable
fun TmdbCredit(tmdb: TmdbMeta?) {
    if (tmdb == null || (tmdb.reliableRating == null && tmdb.overview == null)) return
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.drawable.tmdb_logo),
            contentDescription = stringResource(R.string.tmdb_logo_description),
            modifier = Modifier.height(16.dp),
        )
        Text(
            stringResource(R.string.tmdb_credit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

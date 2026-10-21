package com.keyspaceapps.goodtv.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.data.sports.ChannelOrder
import com.keyspaceapps.goodtv.data.sports.GameState
import com.keyspaceapps.goodtv.data.sports.GameWithChannels
import com.keyspaceapps.goodtv.data.sports.SportsBoard
import com.keyspaceapps.goodtv.data.sports.Team
import com.keyspaceapps.goodtv.domain.label
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import kotlinx.coroutines.flow.Flow

private val LiveRed = Color(0xFFE11D48)

/**
 * The Sports tab: a row per league with today's games that are on now or later, live first.
 * Selecting a game on one channel plays it; on several, a picker lists them ([ChannelOrder]); a
 * game with no channel shows its broadcasters (or "Channel not known") and plays nothing.
 * [board] replaces the real data in tests.
 */
@Composable
fun SportsScreen(onPlay: (PlayerKey) -> Unit, board: Flow<SportsBoard>? = null) {
    val vm = goodTvViewModel { SportsViewModel(board ?: it.sports.observe()) }
    val current by vm.board.collectAsState()
    var picking by remember { mutableStateOf<GameWithChannels?>(null) }
    val requesters = remember { HashMap<String, FocusRequester>() }
    var initialDone by remember { mutableStateOf(false) }

    fun play(c: ChannelCandidate) = onPlay(PlayerKey(c.channel.sourceId, PlayerKey.KIND_LIVE, c.channel.id, null, c.channel.label))

    // Focus goes back to the card before the picker leaves; removing the focused row first would
    // let Compose move focus to the first card (and remember that one instead).
    fun closePicker(g: GameWithChannels) {
        requesters[g.game.key]?.requestFocus()
        picking = null
    }

    val loaded = current ?: run {
        Text(stringResource(R.string.sports_loading), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp, top = 24.dp))
        return
    }
    Box(Modifier.fillMaxSize()) {
        if (loaded.leagues.isEmpty()) {
            Text(stringResource(R.string.sports_none_today), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp, top = 24.dp))
        } else {
            val target = vm.focus.key?.takeIf { k -> loaded.leagues.any { l -> l.games.any { it.game.key == k } } }
                ?: loaded.leagues.firstOrNull { it.games.isNotEmpty() }?.games?.first()?.game?.key
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                items(loaded.leagues, key = { it.league.name }) { row ->
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text(row.league.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
                        if (row.failed) {
                            Text(
                                stringResource(R.string.sports_failed, row.league.label),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                            )
                        } else {
                            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(row.games, key = { it.game.key }) { g ->
                                    val requester = remember { FocusRequester() }
                                    DisposableEffect(g.game.key) {
                                        requesters[g.game.key] = requester
                                        onDispose { requesters.remove(g.game.key) }
                                    }
                                    GameCard(
                                        g,
                                        onClick = {
                                            when (g.channels.size) {
                                                0 -> Unit
                                                1 -> play(g.channels.single())
                                                else -> picking = g
                                            }
                                        },
                                        modifier = Modifier.focusRequester(requester)
                                            .onFocusChanged { if (it.isFocused) vm.focus.key = g.game.key }
                                            .onPlaced { if (!initialDone && g.game.key == target) { initialDone = true; requester.requestFocus() } },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        picking?.let { g ->
            ChannelPanel(g, onPick = { closePicker(g); play(it) }, onClose = { closePicker(g) }, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun GameCard(g: GameWithChannels, onClick: () -> Unit, modifier: Modifier) {
    val game = g.game
    val context = LocalContext.current
    Card(onClick = onClick, modifier = modifier.width(300.dp).height(168.dp)) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamCell(game.away, Modifier.weight(1f))
                Text("@", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
                TeamCell(game.home, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (game.state == GameState.LIVE) {
                    Text(
                        stringResource(R.string.sports_live),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.background(LiveRed, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    if (game.awayScore != null && game.homeScore != null) {
                        Text("${game.awayScore} – ${game.homeScore}", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    }
                    game.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    Text(SportsFormats.startTime(context, game.startsAt), style = MaterialTheme.typography.titleSmall)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    g.channels.isNotEmpty() -> pluralStringResource(R.plurals.sports_on_channels, g.channels.size, g.channels.size)
                    game.broadcasters.isNotEmpty() -> game.broadcasters.joinToString(" · ")
                    else -> stringResource(R.string.sports_channel_unknown)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Logo (or the abbreviation in a circle while it loads or when there is none) above the team's short name. */
@Composable
private fun TeamCell(team: Team, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Text(team.abbreviation, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            team.logoUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        Text(team.nickname ?: team.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Knicks @ 76ers" and the game's channels, North American and English first; Back closes it. */
@Composable
private fun ChannelPanel(g: GameWithChannels, onPick: (ChannelCandidate) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val channels = remember(g) { ChannelOrder.sort(g.channels) }
    val first = remember { FocusRequester() }
    var requested by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxHeight().width(520.dp)
            .background(Color(0xFF0B1220).copy(alpha = 0.96f), RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
            .padding(horizontal = 28.dp, vertical = 36.dp)
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) { if (e.type == KeyEventType.KeyUp) onClose(); true } else false
            },
    ) {
        Text(
            "${g.game.away.nickname ?: g.game.away.displayName} @ ${g.game.home.nickname ?: g.game.home.displayName}",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(channels, key = { _, c -> "${c.channel.sourceId}:${c.channel.id}" }) { i, c ->
                ListItem(
                    selected = false,
                    onClick = { onPick(c) },
                    headlineContent = { Text(c.channel.label.ifBlank { c.channel.name }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(c.categoryName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black,
                    ),
                    modifier = if (i == 0) Modifier.focusRequester(first).onPlaced { if (!requested) { requested = true; first.requestFocus() } } else Modifier,
                )
            }
        }
    }
}

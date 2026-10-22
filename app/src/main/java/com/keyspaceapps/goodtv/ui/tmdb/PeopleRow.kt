package com.keyspaceapps.goodtv.ui.tmdb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.tmdb.PersonJob
import com.keyspaceapps.goodtv.data.tmdb.TmdbPerson

/** "People": TMDB's directors or creators and cast for a title, one button each; draws nothing when [people] is empty. */
@Composable
fun PeopleRow(people: List<TmdbPerson>, onPerson: (TmdbPerson) -> Unit, modifier: Modifier = Modifier) {
    if (people.isEmpty()) return
    Column(modifier) {
        Text(stringResource(R.string.detail_people), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(people, key = { it.id }) { p ->
                Button(onClick = { onPerson(p) }, modifier = Modifier.testTag("person-${p.id}")) {
                    Column {
                        Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        role(p)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalContentColor.current.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun role(p: TmdbPerson): String? = when (p.job) {
    PersonJob.CAST -> p.role
    PersonJob.DIRECTOR -> stringResource(R.string.role_director)
    PersonJob.CREATOR -> stringResource(R.string.role_creator)
}

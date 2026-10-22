package com.keyspaceapps.goodtv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.GoodTvKey
import com.keyspaceapps.goodtv.ui.nav.PersonSearchKey

/**
 * Home search opened from a person on an info page: their name in the box, their library titles
 * first, then ordinary title matches. Its own search state, so Home's query is left alone. Focus
 * starts on the person's first poster, else the search bar; Back returns to the info page.
 */
@Composable
fun PersonSearchScreen(key: PersonSearchKey, onNavigate: (GoodTvKey) -> Unit) {
    val vm = goodTvViewModel(key = "person-${key.personId}") { HomeSearchViewModel(it.repository, it.people, key) }
    SearchPane(
        vm, onNavigate, claimFocus = true,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 24.dp),
    )
}

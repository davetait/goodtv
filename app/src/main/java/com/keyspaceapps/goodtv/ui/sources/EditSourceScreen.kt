package com.keyspaceapps.goodtv.ui.sources

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.ui.goodTvViewModel

@Composable
fun EditSourceScreen(sourceId: Long?, onDone: () -> Unit) {
    val vm = goodTvViewModel(key = "edit-$sourceId") { EditSourceViewModel(it.repository, sourceId) }
    val form by vm.form.collectAsState()
    val doneFocus = remember { FocusRequester() }
    var doneFocusRequested by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text(
            stringResource(if (vm.isEdit) R.string.edit_source else R.string.add_source),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(24.dp))
        val fieldWidth = Modifier.width(640.dp)
        TvTextField(form.nickname, { vm.update(nickname = it) }, stringResource(R.string.field_nickname), fieldWidth)
        Spacer(Modifier.height(16.dp))
        TvTextField(form.server, { vm.update(server = it) }, stringResource(R.string.field_server), fieldWidth)
        Spacer(Modifier.height(16.dp))
        TvTextField(form.username, { vm.update(username = it) }, stringResource(R.string.field_username), fieldWidth)
        Spacer(Modifier.height(16.dp))
        TvTextField(form.password, { vm.update(password = it) }, stringResource(R.string.field_password), fieldWidth, password = true, last = true)
        Spacer(Modifier.height(24.dp))
        if (form.saved) {
            // Saved: show the sign-in result and let the user leave; the first sync runs in the background.
            form.result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.focusRequester(doneFocus).onPlaced {
                    if (!doneFocusRequested) { doneFocusRequested = true; doneFocus.requestFocus() }
                },
            ) { Text(stringResource(R.string.done)) }
        } else {
            Button(onClick = { vm.testAndSave() }, enabled = !form.testing) {
                Text(stringResource(if (form.testing) R.string.testing else R.string.test_and_save))
            }
            Spacer(Modifier.height(16.dp))
            form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

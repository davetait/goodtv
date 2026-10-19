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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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

    LaunchedEffect(form.saved) { if (form.saved) onDone() }

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
        Button(onClick = { vm.testAndSave() }, enabled = !form.testing) {
            Text(stringResource(if (form.testing) R.string.testing else R.string.test_and_save))
        }
        Spacer(Modifier.height(16.dp))
        form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        form.result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}

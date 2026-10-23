package com.keyspaceapps.goodtv.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.sources.TvTextField

private const val PASSWORD_DOTS = "••••••••"
private val FieldWidth = 640.dp

/**
 * TMDB and OpenSubtitles keys. Fields start empty: a saved value shows only as the masked
 * placeholder, and an empty field keeps it. Save checks what was typed before storing it.
 */
@Composable
fun IntegrationsScreen() {
    val vm = goodTvViewModel(key = "integrations") { IntegrationsViewModel(it.integrations, it.integrationChecks, it.appScope) }
    val tmdb by vm.tmdb.collectAsState()
    val os by vm.openSubtitles.collectAsState()

    var tmdbKey by rememberSaveable { mutableStateOf("") }
    var osKey by rememberSaveable { mutableStateOf("") }
    var osUsername by rememberSaveable { mutableStateOf("") }
    var osPassword by rememberSaveable { mutableStateOf("") }
    ClearAfterSave(tmdb.saves) { tmdbKey = "" }
    ClearAfterSave(os.saves) { osKey = ""; osUsername = ""; osPassword = "" }

    val tmdbField = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }
    val tmdbSave = remember { FocusRequester() }
    val osSave = remember { FocusRequester() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text(stringResource(R.string.integrations_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.integrations_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.integrations_phone_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))

        IntegrationCard(
            name = stringResource(R.string.tmdb_name),
            ui = tmdb,
            what = stringResource(R.string.tmdb_what),
            where = withBoldDomain(R.string.tmdb_where, "themoviedb.org"),
            save = { vm.saveTmdb(tmdbKey) },
            remove = { vm.removeTmdb(); tmdbSave.requestFocus() },
            saveFocus = tmdbSave,
            saveTag = "tmdb-save",
        ) {
            val label = stringResource(R.string.tmdb_field)
            TvTextField(
                tmdbKey, { tmdbKey = it }, label, Modifier.width(FieldWidth),
                last = true,
                placeholder = tmdb.maskedKey ?: label,
                fieldModifier = Modifier
                    .testTag("tmdb-field")
                    .focusRequester(tmdbField)
                    .focusProperties { down = tmdbSave }
                    .onPlaced {
                        if (!initialFocusRequested) { initialFocusRequested = true; tmdbField.requestFocus() }
                    },
            )
        }
        Spacer(Modifier.height(24.dp))

        IntegrationCard(
            name = stringResource(R.string.os_name),
            ui = os,
            what = stringResource(R.string.os_what),
            where = withBoldDomain(R.string.os_where, "opensubtitles.com"),
            save = { vm.saveOpenSubtitles(osKey, osUsername, osPassword) },
            remove = { vm.removeOpenSubtitles(); osSave.requestFocus() },
            saveFocus = osSave,
            saveTag = "os-save",
            why = stringResource(R.string.os_why),
        ) {
            val keyLabel = stringResource(R.string.os_key)
            val userLabel = stringResource(R.string.os_username)
            val passwordLabel = stringResource(R.string.os_password)
            TvTextField(osKey, { osKey = it }, keyLabel, Modifier.width(FieldWidth), placeholder = os.maskedKey ?: keyLabel)
            Spacer(Modifier.height(12.dp))
            TvTextField(osUsername, { osUsername = it }, userLabel, Modifier.width(FieldWidth), placeholder = os.username ?: userLabel)
            Spacer(Modifier.height(12.dp))
            TvTextField(
                osPassword, { osPassword = it }, passwordLabel, Modifier.width(FieldWidth),
                password = true, last = true,
                placeholder = if (os.maskedPassword) PASSWORD_DOTS else passwordLabel,
                fieldModifier = Modifier.focusProperties { down = osSave },
            )
        }
    }
}

/** Clears the typed fields when [saves] goes up (values were stored), not on first show or after recreation. */
@Composable
private fun ClearAfterSave(saves: Int, clear: () -> Unit) {
    var seen by rememberSaveable { mutableIntStateOf(saves) }
    LaunchedEffect(saves) {
        if (saves != seen) { seen = saves; clear() }
    }
}

@Composable
private fun withBoldDomain(@StringRes res: Int, domain: String): AnnotatedString {
    val text = stringResource(res, domain)
    val start = text.indexOf(domain)
    val bold = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    return buildAnnotatedString {
        append(text)
        if (start >= 0) addStyle(bold, start, start + domain.length)
    }
}

@Composable
private fun IntegrationCard(
    name: String,
    ui: CardUi,
    what: String,
    where: AnnotatedString,
    save: () -> Unit,
    remove: () -> Unit,
    saveFocus: FocusRequester,
    saveTag: String,
    why: String? = null,
    fields: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .widthIn(max = 760.dp)
            .background(colors.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge)
            StatusBadge(ui.badge)
            if (ui.fromBuild) Text(stringResource(R.string.from_build), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text(what, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text(where, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        if (why != null) {
            Spacer(Modifier.height(4.dp))
            Text(why, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        fields()
        ui.message?.let {
            Spacer(Modifier.height(12.dp))
            // "Saved; couldn't check" is news, not an error; everything else here is a refusal.
            val saved = it == R.string.tmdb_unreachable || it == R.string.os_unreachable
            Text(stringResource(it), color = if (saved) colors.onSurfaceVariant else colors.error)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Stays enabled while checking, so focus doesn't jump; the view model ignores a second press.
            Button(onClick = save, modifier = Modifier.focusRequester(saveFocus).testTag(saveTag)) { Text(stringResource(R.string.save)) }
            if (ui.removable) Button(onClick = remove) { Text(stringResource(R.string.remove)) }
        }
    }
}

@Composable
private fun StatusBadge(badge: Badge) {
    val (label, tint) = when (badge) {
        Badge.NOT_SET -> R.string.badge_not_set to Color(0xFFE7B860)
        Badge.CHECKING -> R.string.badge_checking to MaterialTheme.colorScheme.onSurfaceVariant
        Badge.CONNECTED -> R.string.badge_connected to MaterialTheme.colorScheme.primary
        Badge.REJECTED -> R.string.badge_rejected to MaterialTheme.colorScheme.error
        Badge.UNCHECKED -> R.string.badge_unchecked to Color(0xFFE7B860)
    }
    Box(
        Modifier
            .background(tint.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

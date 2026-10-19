package com.keyspaceapps.goodtv.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.xtream.XtreamUrls
import com.keyspaceapps.goodtv.domain.SourceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.DateFormat
import java.util.Date

class EditSourceViewModel(private val repo: LibraryRepository, private val sourceId: Long?) : ViewModel() {
    data class Form(
        val nickname: String = "",
        val server: String = "",
        val username: String = "",
        val password: String = "",
        val testing: Boolean = false,
        val result: String? = null,
        val error: String? = null,
        val saved: Boolean = false,
    )

    private val _form = MutableStateFlow(Form())
    val form: StateFlow<Form> = _form
    val isEdit: Boolean get() = sourceId != null

    init {
        if (sourceId != null) {
            viewModelScope.launch {
                val record = repo.source(sourceId) ?: return@launch
                val cfg = record.config as? SourceConfig.Xtream ?: return@launch
                _form.update { it.copy(nickname = record.nickname, server = cfg.server, username = cfg.username, password = cfg.password) }
            }
        }
    }

    fun update(nickname: String? = null, server: String? = null, username: String? = null, password: String? = null) {
        _form.update {
            it.copy(
                nickname = nickname ?: it.nickname, server = server ?: it.server,
                username = username ?: it.username, password = password ?: it.password,
                error = null, result = null,
            )
        }
    }

    fun testAndSave() {
        val f = _form.value
        val validationError = when {
            f.server.isBlank() -> "Server URL is required"
            f.username.isBlank() -> "Username is required"
            f.password.isBlank() -> "Password is required"
            else -> null
        }
        if (validationError != null) {
            _form.update { it.copy(error = validationError) }
            return
        }
        val server = XtreamUrls.normalizeServer(f.server)
        val config = SourceConfig.Xtream(server, f.username.trim(), f.password)
        _form.update { it.copy(testing = true, error = null, result = null) }
        viewModelScope.launch {
            repo.validate(config).fold(
                onSuccess = { info ->
                    val nickname = f.nickname.trim().ifBlank { server.toHttpUrlOrNull()?.host ?: server }
                    val saved = config.copy(allowedOutputFormats = info.allowedOutputFormats)
                    if (sourceId == null) repo.addSource(nickname, saved) else repo.updateSource(sourceId, nickname, saved)
                    _form.update { it.copy(testing = false, result = describe(info), saved = true) }
                },
                onFailure = { e -> _form.update { it.copy(testing = false, error = e.message ?: "Unknown error") } },
            )
        }
    }

    private fun describe(info: SourceInfo): String = buildString {
        append(info.status)
        info.expiresAt?.let { append(" · expires ").append(DateFormat.getDateInstance().format(Date(it))) }
        info.maxConnections?.let { append(" · ").append(it).append(" connections") }
    }
}

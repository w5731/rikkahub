package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.aurora.AuroraImageService
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.AuroraArtistPreset
import me.rerere.rikkahub.data.model.AuroraImageConfig
import me.rerere.rikkahub.data.model.AuroraToken
import kotlin.uuid.Uuid

class AuroraImageVM(
    private val settingsStore: SettingsStore,
    private val auroraImageService: AuroraImageService,
) : ViewModel() {
    val config = settingsStore.settingsFlow
        .map { it.auroraImageConfig }
        .stateIn(viewModelScope, SharingStarted.Lazily, AuroraImageConfig())

    /** 正在查询余额的 token id 集合。 */
    private val _checkingTokens = MutableStateFlow<Set<String>>(emptySet())
    val checkingTokens = _checkingTokens.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    fun update(transform: (AuroraImageConfig) -> AuroraImageConfig) {
        viewModelScope.launch {
            settingsStore.update { it.copy(auroraImageConfig = transform(it.auroraImageConfig)) }
        }
    }

    fun checkPoints(tokenId: String) {
        viewModelScope.launch {
            val cfg = settingsStore.settingsFlow.value.auroraImageConfig
            val token = cfg.tokens.firstOrNull { it.id == tokenId } ?: return@launch
            if (token.token.isBlank()) {
                _messages.tryEmit("Token 为空，无法查询余额")
                return@launch
            }
            _checkingTokens.update { it + tokenId }
            val result = auroraImageService.fetchPoints(cfg.baseUrl, token.token)
            _checkingTokens.update { it - tokenId }
            result.fold(
                onSuccess = { points ->
                    update { c ->
                        c.copy(
                            tokens = c.tokens.map {
                                if (it.id == tokenId) {
                                    it.copy(points = points, lastCheckedAt = System.currentTimeMillis())
                                } else it
                            }
                        )
                    }
                },
                onFailure = { e ->
                    _messages.tryEmit("余额查询失败: ${e.message ?: e.javaClass.simpleName}")
                },
            )
        }
    }

    fun addToken(name: String, token: String) = update { c ->
        c.copy(tokens = c.tokens + AuroraToken(name = name.trim(), token = token.trim()))
    }

    fun updateToken(id: String, name: String, token: String) = update { c ->
        c.copy(
            tokens = c.tokens.map {
                if (it.id == id) {
                    it.copy(
                        name = name.trim(),
                        token = token.trim(),
                        points = if (token.trim() != it.token) null else it.points,
                        lastCheckedAt = if (token.trim() != it.token) null else it.lastCheckedAt,
                    )
                } else it
            }
        )
    }

    fun deleteToken(id: String) = update { c ->
        c.copy(tokens = c.tokens.filterNot { it.id == id })
    }

    /** 新增（id 为空时生成）或按 id 覆盖画师预设。 */
    fun savePreset(preset: AuroraArtistPreset) = update { c ->
        val p = preset.copy(
            id = preset.id.ifBlank { "custom-" + Uuid.random().toString().take(8) },
            name = preset.name.trim().ifBlank { "未命名预设" },
        )
        val exists = c.artistPresets.any { it.id == p.id }
        c.copy(
            artistPresets = if (exists) {
                c.artistPresets.map { if (it.id == p.id) p else it }
            } else {
                c.artistPresets + p
            }
        )
    }

    fun deletePreset(id: String) = update { c ->
        c.copy(artistPresets = c.artistPresets.filterNot { it.id == id })
    }
}

package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import coil3.ImageLoader
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.aurora.resolveAuroraRequestUrl
import me.rerere.rikkahub.data.db.entity.MarkdownRemoteImageEntity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MarkdownRemoteImageRepository
import kotlin.uuid.Uuid

private const val AURORA_REQUEST_TIMEOUT_MS = 180_000

class MarkdownImagesVM(
    private val markdownRemoteImageRepository: MarkdownRemoteImageRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val items = markdownRemoteImageRepository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList<MarkdownRemoteImageEntity>(),
    )

    fun delete(id: String) {
        viewModelScope.launch {
            markdownRemoteImageRepository.deleteById(id)
        }
    }

    /** 管理页刷新：艾罗拉稳定 URL 在此附加 token 并 nocache 重新生成，其余图片原样刷新。 */
    suspend fun refreshFromNetwork(imageLoader: ImageLoader, sourceUrl: String): Uri {
        val requestUrl = resolveAuroraRequestUrl(
            sourceUrl,
            settingsStore.settingsFlow.value.auroraImageConfig,
        )
        return markdownRemoteImageRepository.refreshFromNetwork(
            imageLoader = imageLoader,
            sourceUrl = sourceUrl,
            requestUrl = requestUrl,
            appendNocache = requestUrl != null,
            timeoutMs = if (requestUrl != null) AURORA_REQUEST_TIMEOUT_MS else null,
        )
    }

    suspend fun findConversationAndNodeForRemoteUrl(sourceUrl: String): Pair<Uuid, Uuid>? =
        conversationRepository.findConversationAndNodeForRemoteImageUrl(
            sourceUrl,
            auroraConfig = settingsStore.settingsFlow.value.auroraImageConfig,
        )

    /**
     * 根据远程图片 URL 全局查找所在会话标题与消息下标（0-based），供设置页详情展示；
     * 解析规则与 [findConversationAndNodeForRemoteUrl] 一致。
     */
    suspend fun lookupConversationDetailForRemoteUrl(sourceUrl: String): Pair<String, Int>? {
        val ids = findConversationAndNodeForRemoteUrl(sourceUrl) ?: return null
        val conversation = conversationRepository.getConversationById(ids.first) ?: return null
        val index = conversation.messageNodes.indexOfFirst { it.id == ids.second }
        if (index < 0) return null
        return conversation.title.trim() to index
    }
}

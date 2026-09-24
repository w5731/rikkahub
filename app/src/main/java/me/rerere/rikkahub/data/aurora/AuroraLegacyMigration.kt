package me.rerere.rikkahub.data.aurora

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.AuroraImageConfig
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MarkdownRemoteImageRepository
import kotlin.uuid.Uuid

private const val TAG = "AuroraLegacyMigration"

/**
 * 一次性迁移：艾罗拉缓存身份从「含画师串/参数」的旧 URL 切换到「tag+size+seed」。
 *
 * 旧版本生成的消息里占位符没有 seed，图片按旧身份落盘；升级后渲染层用新身份
 * 找不到缓存，旧图全部显示「待生成」。本迁移在每次启动时幂等扫描仍无 seed 的旧消息：
 * 1. 用 [AuroraDrawProtocol.stampLegacyPlaceholders] 给历史助手消息的占位符补盖
 *    确定性 seed（消息 id + 序号，与生成期盖印同一函数）并回写会话；
 * 2. 按盖印前后的占位符一一配对，把旧身份（[buildLegacyAuroraStableUrl]，
 *    用当前配置 + 会话助手预设复原）的本地图片复制到新身份名下。
 *
 * 盖印与 rekey 均幂等：中途失败会在下次启动重跑，已完成的部分自动跳过。
 * 若生成图片后配置（预设/参数/baseUrl）被改动过，旧身份无法复原，
 * 对应图片保持「待生成」，用户可在预览页手动刷新重新生成。
 */
class AuroraLegacyMigration(
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val remoteImageRepo: MarkdownRemoteImageRepository,
) {
    suspend fun run() = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlowRaw.first()
            val config = settings.auroraImageConfig
            val conversationIds = conversationRepo.getConversationIdsContainingAuroraPlaceholders()
            Log.i(TAG, "run: ${conversationIds.size} conversations with placeholders")

            conversationIds.forEach { idStr ->
                val conversation = runCatching { conversationRepo.getConversationById(Uuid.parse(idStr)) }
                    .getOrNull() ?: return@forEach
                val presetId = settings.assistants
                    .firstOrNull { it.id == conversation.assistantId }
                    ?.auroraDrawPresetId

                val updatedNodes = conversationRepo.updateAuroraPlaceholders(conversation.id) { message ->
                    if (message.role != MessageRole.ASSISTANT) return@updateAuroraPlaceholders message
                    val newParts = message.parts.map { part ->
                        if (part is UIMessagePart.Text && AuroraDrawProtocol.hasPlaceholder(part.text)) {
                            val stamped = AuroraDrawProtocol.stampLegacyPlaceholders(
                                text = part.text,
                                seedBase = message.id.toString(),
                            )
                            if (stamped != part.text && rekeyImages(part.text, stamped, config, presetId)) {
                                part.copy(text = stamped)
                            } else {
                                part
                            }
                        } else {
                            part
                        }
                    }
                    if (newParts != message.parts) message.copy(parts = newParts) else message
                }
                if (updatedNodes > 0) {
                    Log.i(TAG, "run: migrated conversation ${conversation.id}, nodes=$updatedNodes")
                }
            }
        }.onFailure {
            Log.e(TAG, "run: migration failed, will retry on next launch", it)
        }
    }

    /** 盖印前后占位符按下标一一配对，旧身份缓存复制到新身份。 */
    private suspend fun rekeyImages(
        before: String,
        after: String,
        config: AuroraImageConfig,
        presetId: String?,
    ): Boolean {
        val legacyPlaceholders = AuroraDrawProtocol.parsePlaceholders(before)
        val stampedPlaceholders = AuroraDrawProtocol.parsePlaceholders(after)
        if (legacyPlaceholders.size != stampedPlaceholders.size) return false
        var success = true
        legacyPlaceholders.zip(stampedPlaceholders).forEach { (legacy, stamped) ->
            val seed = stamped.seed ?: run {
                success = false
                return@forEach
            }
            val computedLegacyUrl = buildLegacyAuroraStableUrl(
                tag = legacy.tag,
                size = legacy.size,
                config = config,
                presetId = presetId,
            )
            val legacyUrl = remoteImageRepo.findLegacyAuroraSourceUrl(
                tag = legacy.tag,
                size = legacy.size.value,
                preferredSourceUrl = computedLegacyUrl,
            ) ?: computedLegacyUrl
            val newUrl = buildAuroraStableUrl(
                tag = stamped.tag,
                size = stamped.size,
                seed = seed,
                baseUrl = config.baseUrl,
            )
            runCatching {
                if (remoteImageRepo.rekeySourceUrl(legacyUrl, newUrl)) {
                    Log.i(TAG, "rekeyed image: ${stamped.tag.take(30)}")
                }
            }.onFailure {
                success = false
                Log.w(TAG, "rekey failed: $legacyUrl", it)
            }
        }
        return success
    }
}

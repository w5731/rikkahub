package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.aurora.extractAuroraStableUrlMatches
import me.rerere.rikkahub.utils.isRemoteHttpUrl

private val MARKDOWN_IMG = Regex("!\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)")
private val HTML_IMG_QUOTED =
    Regex("""<img[^>]*\ssrc\s*=\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
private val HTML_IMG_UNQUOTED =
    Regex("""<img[^>]*\ssrc\s*=\s*(https?://[^\s>]+)""", RegexOption.IGNORE_CASE)

private fun List<UIMessagePart>.collectAllPartsRecursive(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllPartsRecursive() }

/**
 * 按出现顺序提取文本中的远程 http(s) 图片 URL。
 *
 * [auroraConfig] 非空时，同时把 `[[aurora_draw ...]]` 占位符换算成稳定 URL（不含 token，
 * 也不含画师串/参数——缓存身份与配置解耦）一并纳入——渲染层（MarkdownBlock 切分 →
 * AuroraDrawImage）使用同一构建规则，保证全屏预览能按相同身份定位到本地图。
 */
private fun extractMarkdownRemoteHttpUrlsInOrder(
    text: String,
    auroraConfig: AuroraImageConfig? = null,
): List<String> {
    data class M(val start: Int, val url: String)
    val matches = mutableListOf<M>()
    MARKDOWN_IMG.findAll(text).forEach { matches.add(M(it.range.first, it.groupValues[1])) }
    HTML_IMG_QUOTED.findAll(text).forEach { matches.add(M(it.range.first, it.groupValues[1])) }
    HTML_IMG_UNQUOTED.findAll(text).forEach { matches.add(M(it.range.first, it.groupValues[1])) }
    if (auroraConfig != null) {
        extractAuroraStableUrlMatches(text, auroraConfig.baseUrl)
            .forEach { (start, url) -> matches.add(M(start, url)) }
    }
    return matches.sortedBy { it.start }.map { it.url }
}

/**
 * 遍历会话中当前展示的消息文本与独立图片部件，收集远程 http(s) 图片地址；同一 URL 以上下文首次出现顺序保留一次。
 *
 * [assistant] 与聊天渲染一致：对文本使用 `replaceRegexes(..., visual = true)`，
 * 否则界面上的图片 URL 与此处列表不一致，预览无法组成多页横向滑动。
 */
fun Conversation.collectSessionRemoteHttpImageLocations(
    assistant: Assistant?,
    auroraConfig: AuroraImageConfig? = null,
): Map<String, Int> {
    val locations = LinkedHashMap<String, Int>()
    messageNodes.forEachIndexed { index, node ->
        val msg = node.currentMessage
        val regexScope = when (msg.role) {
            MessageRole.USER -> AssistantAffectScope.USER
            else -> AssistantAffectScope.ASSISTANT
        }
        val parts = msg.parts.collectAllPartsRecursive()
        for (part in parts) {
            when (part) {
                is UIMessagePart.Text -> {
                    val visualText = part.text.replaceRegexes(assistant, regexScope, visual = true)
                    extractMarkdownRemoteHttpUrlsInOrder(visualText, auroraConfig)
                        .forEach { locations.putIfAbsent(it, index) }
                }
                is UIMessagePart.Image -> {
                    val url = part.url
                    if (url.isRemoteHttpUrl()) locations.putIfAbsent(url, index)
                }
                else -> Unit
            }
        }
    }
    return locations
}

fun Conversation.collectSessionRemoteHttpImageUrls(
    assistant: Assistant?,
    auroraConfig: AuroraImageConfig? = null,
): List<String> = collectSessionRemoteHttpImageLocations(assistant, auroraConfig).keys.toList()

/**
 * 查找包含指定远程图片 URL 的消息在会话中的下标（与 [collectSessionRemoteHttpImageUrls] 相同的解析规则）。
 */
fun Conversation.findMessageIndexForRemoteImageUrl(
    url: String,
    assistant: Assistant?,
    auroraConfig: AuroraImageConfig? = null,
): Int? {
    messageNodes.forEachIndexed { index, node ->
        val msg = node.currentMessage
        val regexScope = when (msg.role) {
            MessageRole.USER -> AssistantAffectScope.USER
            else -> AssistantAffectScope.ASSISTANT
        }
        val parts = msg.parts.collectAllPartsRecursive()
        for (part in parts) {
            when (part) {
                is UIMessagePart.Text -> {
                    val visualText = part.text.replaceRegexes(assistant, regexScope, visual = true)
                    if (extractMarkdownRemoteHttpUrlsInOrder(visualText, auroraConfig)
                            .any { it == url }
                    ) {
                        return index
                    }
                }
                is UIMessagePart.Image -> {
                    if (part.url == url && url.isRemoteHttpUrl()) {
                        return index
                    }
                }
                else -> Unit
            }
        }
    }
    return null
}

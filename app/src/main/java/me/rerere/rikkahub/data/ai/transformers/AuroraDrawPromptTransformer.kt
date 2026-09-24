package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.aurora.AuroraDrawProtocol
import me.rerere.rikkahub.data.aurora.buildAuroraDrawPrompt

/**
 * 艾罗拉绘图协议注入：
 * - 助手开启绘图且全局配置可用时，把占位符协议注入系统提示（仅影响发送给模型的消息，不落库）；
 * - 功能关闭时，把历史消息中的残留占位符从上下文中剔除，防止模型模仿输出（misskey 同款策略）。
 */
object AuroraDrawPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val config = ctx.settings.auroraImageConfig
        return if (ctx.assistant.enableAuroraDraw && config.isUsable()) {
            injectProtocol(messages, buildAuroraDrawPrompt(config))
        } else {
            stripLegacyPlaceholders(messages)
        }
    }

    private fun injectProtocol(messages: List<UIMessage>, protocol: String): List<UIMessage> {
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        if (systemIndex < 0) {
            return listOf(UIMessage.system(protocol)) + messages
        }
        return messages.mapIndexed { index, message ->
            if (index == systemIndex) {
                val text = message.parts.joinToString("") {
                    (it as? UIMessagePart.Text)?.text.orEmpty()
                }
                message.copy(parts = listOf(UIMessagePart.Text("$text\n\n$protocol")))
            } else {
                message
            }
        }
    }

    private fun stripLegacyPlaceholders(messages: List<UIMessage>): List<UIMessage> {
        var anyChanged = false
        val result = messages.map { message ->
            var msgChanged = false
            val newParts = message.parts.map { part ->
                if (part is UIMessagePart.Text && AuroraDrawProtocol.hasPlaceholder(part.text)) {
                    msgChanged = true
                    part.copy(text = AuroraDrawProtocol.stripPlaceholders(part.text))
                } else {
                    part
                }
            }
            if (msgChanged) {
                anyChanged = true
                message.copy(parts = newParts)
            } else {
                message
            }
        }
        return if (anyChanged) result else messages
    }
}

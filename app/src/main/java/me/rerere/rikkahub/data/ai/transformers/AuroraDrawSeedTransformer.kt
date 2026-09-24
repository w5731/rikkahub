package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.aurora.AuroraDrawProtocol

/**
 * 艾罗拉占位符 seed 盖印：
 *
 * 为助手消息中的 `[[aurora_draw]]` 占位符写入确定性 seed（随消息文本持久化）。
 * seed 是本地缓存身份的一部分，与画师预设/生成参数/token 解耦——
 * 切换预设后历史图片照常显示，只有新生成的图片使用新预设。
 *
 * `transform` 在流式生成的每个 chunk 都会执行（见 GenerationHandler），
 * `onGenerationFinish` 在收尾时幂等再执行一次；两者使用同一种子函数
 * （消息 id + 占位符下标），保证流式渲染与最终落库的缓存身份完全一致。
 */
object AuroraDrawSeedTransformer : OutputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = stamp(ctx, messages)

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = stamp(ctx, messages)

    private fun stamp(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        if (!ctx.assistant.enableAuroraDraw || !ctx.settings.auroraImageConfig.isUsable()) {
            return messages
        }
        return messages.map { message ->
            if (message.role != MessageRole.ASSISTANT) return@map message
            val newParts = message.parts.map { part ->
                if (part is UIMessagePart.Text && AuroraDrawProtocol.hasPlaceholder(part.text)) {
                    part.copy(
                        text = AuroraDrawProtocol.stampPlaceholders(
                            text = part.text,
                            seedBase = message.id.toString(),
                        )
                    )
                } else {
                    part
                }
            }
            if (newParts != message.parts) message.copy(parts = newParts) else message
        }
    }
}

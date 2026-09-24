package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.compositionLocalOf

/**
 * 当前会话内、按消息顺序去重后的 http(s) 图片 URL，用于全屏预览左右滑动与刷新。
 * 非聊天页默认为空列表。
 */
val LocalSessionMarkdownRemoteImageUrls = compositionLocalOf { emptyList<String>() }

/**
 * 聊天页根据远程图片 URL 滚动到对应消息；非聊天为 null。
 */
data class ChatImageNavigation(
    val conversationTitle: String,
    /** 远程 URL 对应消息在会话中的下标（0-based），找不到返回 null。 */
    val messageIndexForRemoteUrl: (String) -> Int?,
    val locateRemoteImageUrl: (String) -> Unit,
)

val LocalChatImageNavigation = compositionLocalOf<ChatImageNavigation?> { null }

/**
 * 仅最后一条、且当前处于流式生成中的消息允许自动从网络拉取远程 Markdown/HTML 图片并落盘；
 * 其余消息（包括历史会话）一律落到「本地无图」占位，由用户在全屏预览中手动刷新。
 *
 * 「本地无图」状态除用户主动刷新外，绝不会自动转入「请求中」；这是流量与图床调用费的硬性约束。
 */
val LocalMarkdownRemoteImageAutoFetch = compositionLocalOf { false }

/**
 * 远程图片的请求规格：网络加载与刷新实际使用的 URL 及附加行为。
 *
 * 缓存身份始终是 [MarkdownRemoteImage] 的 `src`（稳定 URL）；请求 URL 允许与身份不同，
 * 典型场景是艾罗拉生图——稳定 URL 不含 token，请求时才由 resolver 附加 token，
 * token 轮换不会使本地缓存失效；`nocacheOnRefresh` 为 true 时刷新请求追加时间戳以生成新图。
 */
data class RemoteImageRequestSpec(
    val requestUrl: String,
    val nocacheOnRefresh: Boolean = false,
    val timeoutMs: Int? = null,
)

/** 默认恒等：请求 URL 与缓存身份相同，无刷新穿透，默认超时。 */
val LocalMarkdownRemoteImageRequestSpecResolver =
    compositionLocalOf<(String) -> RemoteImageRequestSpec> {
        { RemoteImageRequestSpec(it) }
    }

/**
 * 当前会话的助手级艾罗拉画师预设覆盖；非聊天页为 null（使用全局默认预设）。
 * 只影响请求时附加的画师串/参数（新生成的图），不影响缓存身份——
 * 历史图片的稳定 URL 不含这些参数，切换预设后照常命中本地缓存。
 */
val LocalAuroraDrawPresetId = compositionLocalOf<String?> { null }

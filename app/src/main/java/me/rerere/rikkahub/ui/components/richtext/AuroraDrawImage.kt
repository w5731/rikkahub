package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.aurora.AuroraDrawProtocol
import me.rerere.rikkahub.data.aurora.buildAuroraStableUrl
import me.rerere.rikkahub.data.aurora.resolveAuroraRequestUrl
import me.rerere.rikkahub.data.model.AuroraImageConfig
import me.rerere.rikkahub.ui.context.LocalSettings

/** 艾罗拉生图较慢（misskey 侧超时 180s），HTTP 兜底路径沿用同一上限。 */
private const val AURORA_REQUEST_TIMEOUT_MS = 180_000

private val AuroraCardGradient = Brush.linearGradient(
    listOf(Color(0xFFFFC9D9), Color(0xFFCCE5FF))
)

/**
 * 根据 [config] 构建会话级的远程图片请求规格 resolver：
 * 艾罗拉稳定 URL → 附加当前画师串/参数与 token，刷新时 nocache 穿透生新图；
 * 其余 URL 保持恒等。聊天页与渲染卡片使用同一 resolver，保证预览刷新兄弟图时行为一致。
 *
 * [presetId] 只影响请求 URL（新生成的图），不影响缓存身份——
 * 切换画师预设后历史图片照常命中本地缓存。
 */
fun auroraRequestSpecResolver(
    config: AuroraImageConfig,
    presetId: String? = null,
): (String) -> RemoteImageRequestSpec = { url ->
    val requestUrl = resolveAuroraRequestUrl(url, config, presetId)
    if (requestUrl != null) {
        RemoteImageRequestSpec(
            requestUrl = requestUrl,
            nocacheOnRefresh = true,
            timeoutMs = AURORA_REQUEST_TIMEOUT_MS,
        )
    } else {
        RemoteImageRequestSpec(url)
    }
}

/**
 * 艾罗拉生图卡片：渲染消息正文中的 `[[aurora_draw ...]]` 占位符。
 *
 * 稳定 URL（缓存身份）只由占位符文本（tag/size/seed）与 baseUrl 构建，
 * 与画师预设、生成参数、token 全部解耦；网络请求经 [auroraRequestSpecResolver]
 * 按当前配置附加样式参数与 token。图片本体复用 [MarkdownRemoteImage]
 * 的三态加载/落盘/全屏预览管线。
 */
@Composable
fun AuroraDrawImage(
    placeholder: AuroraDrawProtocol.Placeholder,
    modifier: Modifier = Modifier,
) {
    val config = LocalSettings.current.auroraImageConfig
    val presetId = LocalAuroraDrawPresetId.current
    val requestSpecResolver = remember(config, presetId) { auroraRequestSpecResolver(config, presetId) }

    val stableUrl = remember(placeholder, config.baseUrl) {
        buildAuroraStableUrl(
            tag = placeholder.tag,
            size = placeholder.size,
            seed = placeholder.seed,
            baseUrl = config.baseUrl,
        )
    }

    Box(
        modifier = modifier
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AuroraCardGradient)
            .padding(2.dp),
    ) {
        CompositionLocalProvider(
            LocalMarkdownRemoteImageRequestSpecResolver provides requestSpecResolver,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
            ) {
                MarkdownRemoteImage(
                    src = stableUrl,
                    contentDescription = "AI生成图片",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .padding(4.dp),
                    contentScale = ContentScale.Fit,
                    missingLabel = if (config.isUsable()) null else "AI 绘图未启用（缺少有效 Token）",
                    missingDetail = placeholder.tag,
                    allowNetwork = config.isUsable(),
                )
            }
        }
    }
}

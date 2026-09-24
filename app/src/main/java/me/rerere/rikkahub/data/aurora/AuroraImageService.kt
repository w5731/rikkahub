package me.rerere.rikkahub.data.aurora

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.common.http.await
import me.rerere.rikkahub.data.model.AuroraImageConfig
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Duration.Companion.seconds

/**
 * 艾罗拉网络服务：token 余额查询（GET /api/points，Bearer 鉴权）。
 * 图片本体不走这里，由 MarkdownRemoteImage 的三态加载/落盘管线负责。
 */
class AuroraImageService(
    private val okHttpClient: OkHttpClient,
) {
    /** 查询单个 token 的 points 余额；失败返回异常。 */
    suspend fun fetchPoints(baseUrl: String, token: String): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = baseUrl.trim().trimEnd('/').ifEmpty { AuroraImageConfig.DEFAULT_BASE_URL }
                val request = Request.Builder()
                    .url("$base/api/points")
                    .header("Authorization", "Bearer ${token.trim()}")
                    .build()
                val response = okHttpClient.newBuilder()
                    .callTimeout(15.seconds)
                    .readTimeout(15.seconds)
                    .build()
                    .newCall(request)
                    .await()
                response.use {
                    val body = it.body?.string().orEmpty()
                    check(it.isSuccessful) { "HTTP ${it.code}: ${body.take(200)}" }
                    val points = JsonInstant.parseToJsonElement(body)
                        .jsonObject["points"]?.jsonPrimitive?.int
                    checkNotNull(points) { "响应缺少 points 字段: ${body.take(200)}" }
                    points
                }
            }
        }

    companion object {
        /** 设置页「测试生图」使用的示例标签。 */
        const val TEST_TAG = "1girl, silver hair, blue eyes, smile, upper body, outdoors, blue sky, sunlight"
    }
}

/**
 * 根据当前配置为稳定 URL 附加请求要素：画师串/生成参数（按 [presetId] 解析，
 * null 用全局默认预设）与 token。返回 null 表示该 URL 不是艾罗拉图片（原样使用）
 * 或配置不可用。
 *
 * 稳定 URL 本身不含这些参数（缓存身份与配置解耦），因此历史图片在切换预设后
 * 照常命中本地缓存；只有真正发起请求（首次生图/手动刷新重新生成）才使用当前预设。
 */
fun resolveAuroraRequestUrl(
    stableUrl: String,
    config: AuroraImageConfig,
    presetId: String? = null,
): String? {
    if (!config.isUsable()) return null
    val base = config.baseUrl.trim().trimEnd('/')
    if (base.isEmpty() || !stableUrl.startsWith("$base/api/aurora/")) return null
    val token = config.pickToken()?.token ?: return null
    return "$stableUrl&${buildAuroraStyleQuery(config, presetId)}&token=${encodeQuery(token)}"
}

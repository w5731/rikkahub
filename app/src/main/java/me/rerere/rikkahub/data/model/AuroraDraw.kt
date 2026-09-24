package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 艾罗拉（Aurora）文生图全局配置。
 *
 * 模型在正文中只输出紧凑占位符 `[[aurora_draw size=... tag=...]]`，完整请求 URL
 * 在渲染/请求时由本地配置拼装，token 永不进入消息内容与数据库。
 */
@Serializable
data class AuroraImageConfig(
    val enabled: Boolean = false,
    val baseUrl: String = DEFAULT_BASE_URL,
    val tokens: List<AuroraToken> = emptyList(),
    val defaultModel: String = DEFAULT_MODEL,
    val params: AuroraGenerationParams = AuroraGenerationParams(),
    val defaultNegativePrompt: String = DEFAULT_NEGATIVE_PROMPT,
    val artistPresets: List<AuroraArtistPreset> = DEFAULT_ARTIST_PRESETS,
    val defaultPresetId: String = PRESET_DEFAULT_ANIME,
    val imageCountMin: Int = 5,
    val imageCountMax: Int = 8,
) {
    fun resolvePresets(): List<AuroraArtistPreset> =
        artistPresets.ifEmpty { DEFAULT_ARTIST_PRESETS }

    fun resolvePreset(id: String?): AuroraArtistPreset? =
        if (id.isNullOrBlank()) null else resolvePresets().firstOrNull { it.id == id }

    fun isUsable(): Boolean = enabled && pickToken() != null

    fun pickToken(): AuroraToken? = tokens.firstOrNull { it.enabled && it.token.isNotBlank() }

    companion object {
        const val DEFAULT_BASE_URL = "https://love.auroralove.cc"
        const val DEFAULT_MODEL = "nai-diffusion-4-5-full"
        const val PRESET_DEFAULT_ANIME = "default-anime"

        /** NAI 4.5 Full 对应的质量标签，避免混用旧模型的 highres/absurdres 组合。 */
        const val FALLBACK_PROMPT_SUFFIX = "very aesthetic,masterpiece,no text"

        /** NAI 4.5 Full 的 Heavy UCP。 */
        const val DEFAULT_NEGATIVE_PROMPT =
            "lowres,artistic error,film grain,scan artifacts,worst quality,bad quality," +
                "jpeg artifacts,very displeasing,chromatic aberration,dithering,halftone," +
                "screentone,multiple views,logo,too many watermarks,negative space,blank page"
    }
}

@Serializable
data class AuroraToken(
    val id: String = Uuid.random().toString(),
    val token: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val points: Int? = null,
    val lastCheckedAt: Long? = null,
)

@Serializable
data class AuroraGenerationParams(
    val steps: Int = 28,
    val scale: Float = 10f,
    val cfgRescale: Float = 0.18f,
    val sampler: String = DEFAULT_SAMPLER,
    val noiseSchedule: String = DEFAULT_NOISE_SCHEDULE,
) {
    fun sanitized(): AuroraGenerationParams = copy(
        steps = steps.coerceIn(1, 80),
        scale = scale.coerceIn(0f, 30f),
        cfgRescale = cfgRescale.coerceIn(0f, 1f),
        sampler = sampler.trim().ifBlank { DEFAULT_SAMPLER },
        noiseSchedule = noiseSchedule.trim().ifBlank { DEFAULT_NOISE_SCHEDULE },
    )

    companion object {
        const val DEFAULT_SAMPLER = "k_euler"
        const val DEFAULT_NOISE_SCHEDULE = "karras"
    }
}

@Serializable
data class AuroraArtistPreset(
    val id: String,
    val name: String,
    val promptPrefix: String = "",
    val promptSuffix: String = "",
    val negativePrompt: String = "",
)

/**
 * 内置预设只负责画师与风格；模型专属质量词和 UCP 由全局配置统一提供。
 * 这样切换画师时不会重复堆叠互相冲突的质量标签与负面标签。
 */
val DEFAULT_ARTIST_PRESETS = listOf(
    AuroraArtistPreset(
        id = "default-anime",
        name = "二次元插画",
        promptPrefix = "artist:ningen_mame,artist:ciloranko,artist:sho_(sho_lwlw)",
        promptSuffix = AuroraImageConfig.FALLBACK_PROMPT_SUFFIX,
    ),
    AuroraArtistPreset(
        id = "warm-game-portrait",
        name = "暖色系游戏立绘",
        promptPrefix = "artist:moccha_(mochancc),artist:uminonew,artist:ask_(askzy),artist:liduke,artist:wanke,cinematic lighting,watercolor texture,matte glow",
        promptSuffix = AuroraImageConfig.FALLBACK_PROMPT_SUFFIX,
    ),
    AuroraArtistPreset(
        id = "soft-fantasy",
        name = "轻柔幻想风",
        promptPrefix = "artist:fuzichoco,artist:ask_(askzy),soft lighting,dreamy atmosphere,delicate colors",
        promptSuffix = AuroraImageConfig.FALLBACK_PROMPT_SUFFIX,
    ),
    AuroraArtistPreset(
        id = "clear-sweet",
        name = "清透甜绘风",
        promptPrefix = "artist:ningen_mame,artist:ciloranko,clear colors,soft light,cute illustration,delicate face",
        promptSuffix = AuroraImageConfig.FALLBACK_PROMPT_SUFFIX,
    ),
    AuroraArtistPreset(
        id = "light-thick-paint",
        name = "轻厚涂二次元",
        promptPrefix = "artist:wlop,artist:ask_(askzy),semi-realistic anime,soft rendering,cinematic lighting,rich texture",
        promptSuffix = AuroraImageConfig.FALLBACK_PROMPT_SUFFIX,
        negativePrompt = "flat color",
    ),
    AuroraArtistPreset(
        id = "line-manga",
        name = "日系线稿漫画",
        promptPrefix = "clean lineart,anime coloring,manga style,sharp focus,delicate linework",
        promptSuffix = AuroraImageConfig.FALLBACK_PROMPT_SUFFIX,
        negativePrompt = "blurry",
    ),
)

/** 显式恢复 NAI 4.5 Full 的模型、采样参数与 UCP，不改用户的画师预设。 */
fun AuroraImageConfig.withNai45FullDefaults(): AuroraImageConfig = copy(
    defaultModel = AuroraImageConfig.DEFAULT_MODEL,
    params = AuroraGenerationParams(),
    defaultNegativePrompt = AuroraImageConfig.DEFAULT_NEGATIVE_PROMPT,
)

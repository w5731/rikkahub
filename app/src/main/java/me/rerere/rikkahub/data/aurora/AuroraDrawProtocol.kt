package me.rerere.rikkahub.data.aurora

import me.rerere.rikkahub.data.model.AuroraImageConfig

/**
 * 艾罗拉绘图占位符协议（纯函数，可单测）。
 *
 * 模型在正文中输出 `[[aurora_draw size=portrait tag=1girl, ...]]`，
 * 渲染层解析占位符后用本地配置拼装完整请求 URL；占位符本身保留在消息文本中，
 * 不含 token 与任何生成参数，token 轮换 / 参数调整不会使历史失效。
 *
 * 协议格式参考 misskey 的 [[agent_draw]]（AgentImageService.ts）。
 */
object AuroraDrawProtocol {
    const val PLACEHOLDER_HEAD = "[[aurora_draw"

    /** 严格匹配：可缺省 seed（生成时盖印）与 size（默认 portrait），tag 懒匹配到最近的 ]]。 */
    val DRAW_REGEX =
        Regex("""\[\[aurora_draw(?:\s+seed=(\d+))?(?:\s+size=(portrait|landscape|square))?\s+tag=([\s\S]*?)\]\]""")

    /** 宽松匹配（用于功能关闭时清理历史中的残留占位符）。 */
    val DRAW_ANY_REGEX = Regex("""\[\[aurora_draw\b[\s\S]*?\]\]""")

    private const val STORED_TAG_MAX_LENGTH = 4000
    private const val GENERATED_TAG_MAX_LENGTH = 2400
    private const val GENERATED_TAG_MAX_COUNT = 120

    fun hasPlaceholder(text: String): Boolean =
        text.contains(PLACEHOLDER_HEAD) && DRAW_ANY_REGEX.containsMatchIn(text)

    fun stripPlaceholders(text: String): String =
        if (text.contains(PLACEHOLDER_HEAD)) text.replace(DRAW_ANY_REGEX, "") else text

    data class Placeholder(
        val size: AuroraImageSize,
        val tag: String,
        /**
         * 生成时盖印的确定性种子（随消息文本持久化），作为缓存身份的一部分，
         * 与画师预设/生成参数/token 解耦：切换预设后历史图片身份不变、照常显示。
         */
        val seed: Long? = null,
    )

    /** 解析占位符（不做代码块保护）。 */
    fun parsePlaceholders(text: String): List<Placeholder> =
        DRAW_REGEX.findAll(text).map(::matchToPlaceholder).toList()

    /** 正则匹配 → 占位符；保留历史 tag 的格式，避免改变既有图片的稳定缓存身份。 */
    fun matchToPlaceholder(match: MatchResult): Placeholder = Placeholder(
        seed = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull(),
        size = AuroraImageSize.fromValue(match.groupValues.getOrNull(2))
            ?: AuroraImageSize.PORTRAIT,
        tag = cleanStoredTag(match.groupValues.getOrNull(3).orEmpty()),
    )

    private fun cleanStoredTag(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim().take(STORED_TAG_MAX_LENGTH)

    /** 新生成的占位符只在首次盖 seed 时规范化，历史占位符不会被改写。 */
    private fun cleanGeneratedTag(raw: String): String = normalizePromptTags(
        raw = raw,
        maxLength = GENERATED_TAG_MAX_LENGTH,
        maxTags = GENERATED_TAG_MAX_COUNT,
        blockGeneratedOnlyTags = true,
    )

    /**
     * 为尚未盖印的占位符写入确定性 seed（`[[aurora_draw seed=… …]]`）。
     *
     * seed 由 [seedBase]（消息 id）+ 占位符在文本中的下标派生：流式生成每个 chunk
     * 都会重新执行盖印，同一占位符每次得到相同 seed，因此流式渲染与最终落库的
     * 缓存身份完全一致；已带 seed 的占位符原样保留（幂等）。
     */
    fun stampPlaceholders(text: String, seedBase: String): String {
        if (!hasPlaceholder(text)) return text
        var index = 0
        return DRAW_REGEX.replace(text) { match ->
            val i = index++
            if (!match.groupValues.getOrNull(1).isNullOrEmpty()) {
                match.value
            } else {
                val size = AuroraImageSize.fromValue(match.groupValues.getOrNull(2))
                    ?: AuroraImageSize.PORTRAIT
                val tag = cleanGeneratedTag(match.groupValues.getOrNull(3).orEmpty())
                "$PLACEHOLDER_HEAD seed=${seedFor(seedBase, i)} size=${size.value} tag=$tag]]"
            }
        }
    }

    /**
     * 历史迁移专用：仅补 seed 和缺省 size，逐字保留 tag，避免改变旧 URL 的 tag 部分。
     */
    fun stampLegacyPlaceholders(text: String, seedBase: String): String {
        if (!hasPlaceholder(text)) return text
        var index = 0
        return DRAW_REGEX.replace(text) { match ->
            val i = index++
            if (!match.groupValues.getOrNull(1).isNullOrEmpty()) {
                match.value
            } else {
                val size = AuroraImageSize.fromValue(match.groupValues.getOrNull(2))
                    ?: AuroraImageSize.PORTRAIT
                val rawTag = match.groupValues.getOrNull(3).orEmpty()
                "$PLACEHOLDER_HEAD seed=${seedFor(seedBase, i)} size=${size.value} tag=$rawTag]]"
            }
        }
    }

    /** 消息内确定性种子：9 位数字，直接作为请求 URL 的 nocache 值使用。 */
    private fun seedFor(seedBase: String, index: Int): Long {
        var h = seedBase.hashCode().toLong() and 0xFFFF_FFFFL
        h = h * 1_000_003L + index * 97L
        h = h xor (h ushr 16)
        return h % 900_000_000L + 100_000_000L
    }
}

enum class AuroraImageSize(val value: String) {
    PORTRAIT("portrait"),
    LANDSCAPE("landscape"),
    SQUARE("square");

    companion object {
        fun fromValue(v: String?): AuroraImageSize? =
            entries.firstOrNull { it.value.equals(v?.trim(), ignoreCase = true) }
    }
}

/** 消息正文按占位符切分后的片段。 */
sealed interface AuroraDrawSegment {
    data class Text(val text: String) : AuroraDrawSegment

    data class Draw(val placeholder: AuroraDrawProtocol.Placeholder) : AuroraDrawSegment
}

/**
 * 把正文切成文本片段与占位符片段；代码块（``` / ~~~ 围栏）内的占位符不切分。
 */
fun splitAuroraDrawContent(text: String): List<AuroraDrawSegment> {
    if (!text.contains(AuroraDrawProtocol.PLACEHOLDER_HEAD)) {
        return listOf(AuroraDrawSegment.Text(text))
    }
    val codeRanges = fencedCodeRanges(text)
    val matches = AuroraDrawProtocol.DRAW_REGEX.findAll(text)
        .filter { m -> codeRanges.none { m.range.first in it } }
        .toList()
    if (matches.isEmpty()) return listOf(AuroraDrawSegment.Text(text))

    val segments = mutableListOf<AuroraDrawSegment>()
    var cursor = 0
    for (m in matches) {
        if (m.range.first > cursor) {
            segments.add(AuroraDrawSegment.Text(text.substring(cursor, m.range.first)))
        }
        segments.add(AuroraDrawSegment.Draw(AuroraDrawProtocol.matchToPlaceholder(m)))
        cursor = m.range.last + 1
    }
    if (cursor < text.length) {
        segments.add(AuroraDrawSegment.Text(text.substring(cursor)))
    }
    return segments
}

/** 围栏代码块在原文中的字符区间；未闭合的围栏延伸到文本末尾。 */
private fun fencedCodeRanges(text: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var fenceStart: Int? = null
    var cursor = 0
    for (line in text.lineSequence()) {
        val lineStart = cursor
        cursor += line.length + 1 // +1 换行符（末行无换行也无妨，区间计算不越界使用）
        val fence = FENCE_LINE.find(line) ?: continue
        if (fenceStart == null) {
            fenceStart = lineStart
        } else {
            ranges.add(fenceStart..(cursor - 1))
            fenceStart = null
        }
    }
    fenceStart?.let { ranges.add(it..(text.length - 1)) }
    return ranges
}

private val FENCE_LINE = Regex("""^\s{0,3}(`{3,}|~{3,}).*$""")

/**
 * 稳定身份 URL：只含 tag / size / nocache（占位符 seed），
 * 不含画师串、生成参数与 token——这些只在请求时附加（见 [buildAuroraStyleQuery]）。
 *
 * 这是本地缓存身份：切换画师预设、调整参数、轮换 token 都不会改变它，
 * 已落盘的历史图片照常显示；同一 tag 的多个占位符靠 seed 区分，各生成各的图。
 */
fun buildAuroraStableUrl(
    tag: String,
    size: AuroraImageSize,
    seed: Long? = null,
    baseUrl: String,
): String {
    val base = baseUrl.trim().trimEnd('/').ifEmpty { AuroraImageConfig.DEFAULT_BASE_URL }
    val query = buildString {
        append("tag=").append(encodeQuery(tag))
        append("&size=").append(size.value)
        seed?.let { append("&nocache=").append(it) }
    }
    return "$base/api/aurora/regex-image?$query"
}

/**
 * 画师串与生成参数查询串（prompt_prefix/prompt_suffix/negative/model/steps 等），
 * 只在实际请求时按当前配置附加，不参与缓存身份——切换预设只影响之后的新请求。
 */
fun buildAuroraStyleQuery(config: AuroraImageConfig, presetId: String? = null): String {
    val preset = config.resolvePreset(presetId) ?: config.resolvePreset(config.defaultPresetId)
    val promptPrefix = preset?.promptPrefix.orEmpty().trim().trim(',')
    val promptSuffix = (
        preset?.promptSuffix?.takeIf { it.isNotBlank() }
            ?: AuroraImageConfig.FALLBACK_PROMPT_SUFFIX
        ).trim().trim(',')
    val negative = mergeNegativePrompts(config.defaultNegativePrompt, preset?.negativePrompt)
    val params = config.params.sanitized()

    return buildString {
        append("prompt_prefix=").append(encodeQuery(promptPrefix))
        append("&prompt_suffix=").append(encodeQuery(promptSuffix))
        append("&negative=").append(encodeQuery(negative))
        append("&model=").append(encodeQuery(config.defaultModel.ifBlank { AuroraImageConfig.DEFAULT_MODEL }))
        append("&steps=").append(params.steps)
        append("&scale=").append(formatNumber(params.scale))
        append("&cfg_rescale=").append(formatNumber(params.cfgRescale))
        append("&sampler=").append(encodeQuery(params.sampler))
        append("&noise_schedule=").append(encodeQuery(params.noiseSchedule))
    }
}

/**
 * 一次性构建完整请求 URL（稳定身份 + 样式参数 + token），供设置页「测试生图」等
 * 无需缓存身份分离的场景使用；消息渲染链路请改用 [buildAuroraStableUrl] +
 * [resolveAuroraRequestUrl] 的两段式构建。
 *
 * @param nocache 非空时作为 nocache 参数（重新生成新图）
 */
fun buildAuroraImageUrl(
    tag: String,
    size: AuroraImageSize,
    config: AuroraImageConfig,
    presetId: String? = null,
    token: String? = null,
    nocache: Long? = null,
): String = buildString {
    append(buildAuroraStableUrl(tag = tag, size = size, seed = nocache, baseUrl = config.baseUrl))
    append('&').append(buildAuroraStyleQuery(config, presetId))
    token?.takeIf { it.isNotBlank() }?.let {
        append("&token=").append(encodeQuery(it))
    }
}

/**
 * 迁移专用：复刻首版（无 seed 时代）的稳定身份 URL——画师串与全部生成参数
 * 都编在身份里，参数顺序为 tag, prompt_prefix, prompt_suffix, negative, size,
 * model, steps, scale, cfg_rescale, sampler, noise_schedule。
 * AuroraLegacyMigration 用它找到旧缓存并 re-key 到新身份，请勿用于新代码。
 */
fun buildLegacyAuroraStableUrl(
    tag: String,
    size: AuroraImageSize,
    config: AuroraImageConfig,
    presetId: String? = null,
): String {
    val preset = config.resolvePreset(presetId) ?: config.resolvePreset(config.defaultPresetId)
    val promptPrefix = preset?.promptPrefix.orEmpty()
    val promptSuffix = preset?.promptSuffix ?: LEGACY_FALLBACK_PROMPT_SUFFIX
    val negative = mergeLegacyNegativePrompts(config.defaultNegativePrompt, preset?.negativePrompt)
    val params = config.params.sanitized()

    val base = config.baseUrl.trim().trimEnd('/').ifEmpty { AuroraImageConfig.DEFAULT_BASE_URL }
    val query = buildString {
        append("tag=").append(encodeQuery(tag))
        append("&prompt_prefix=").append(encodeQuery(promptPrefix))
        append("&prompt_suffix=").append(encodeQuery(promptSuffix))
        append("&negative=").append(encodeQuery(negative))
        append("&size=").append(size.value)
        append("&model=").append(encodeQuery(config.defaultModel.ifBlank { AuroraImageConfig.DEFAULT_MODEL }))
        append("&steps=").append(params.steps)
        append("&scale=").append(formatNumber(params.scale))
        append("&cfg_rescale=").append(formatNumber(params.cfgRescale))
        append("&sampler=").append(encodeQuery(params.sampler))
        append("&noise_schedule=").append(encodeQuery(params.noiseSchedule))
    }
    return "$base/api/aurora/regex-image?$query"
}

private const val LEGACY_FALLBACK_PROMPT_SUFFIX = "masterpiece,best quality,very aesthetic,highres"

private fun mergeLegacyNegativePrompts(vararg parts: String?): String {
    val seen = LinkedHashSet<String>()
    parts.forEach { part ->
        part.orEmpty().split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { seen.add(it) }
    }
    return seen.joinToString(",")
}

/** 全局默认负面词 + 预设负面词按基础标签合并去重。 */
fun mergeNegativePrompts(vararg parts: String?): String = normalizePromptTags(
    raw = parts.filterNotNull().joinToString(","),
    maxLength = Int.MAX_VALUE,
    maxTags = Int.MAX_VALUE,
    blockGeneratedOnlyTags = false,
)

private fun normalizePromptTags(
    raw: String,
    maxLength: Int,
    maxTags: Int,
    blockGeneratedOnlyTags: Boolean,
): String {
    val tags = ArrayList<String>()
    val keyToIndex = LinkedHashMap<String, Int>()
    raw.replace('\n', ',').replace('\r', ',').split(',').forEach { segment ->
        val tag = segment
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', ';', '，', '。', '；')
        if (tag.isEmpty() || !tag.isAsciiPromptTag()) return@forEach
        val key = promptTagKey(tag)
        if (key.isEmpty() || blockGeneratedOnlyTags && isGeneratedTagBlocked(key)) return@forEach

        val existingIndex = keyToIndex[key]
        if (existingIndex == null) {
            if (tags.size >= maxTags) return@forEach
            val nextLength = tags.sumOf { it.length } + tag.length + tags.size
            if (nextLength > maxLength) return@forEach
            keyToIndex[key] = tags.size
            tags.add(tag)
        } else if (promptTagWeight(tag) > promptTagWeight(tags[existingIndex])) {
            tags[existingIndex] = tag
        }
    }
    return tags.joinToString(",")
}

private fun String.isAsciiPromptTag(): Boolean =
    all { it.code in 0x20..0x7E } &&
        !contains('<') && !contains('>') && !contains("http", ignoreCase = true)

private val GENERATED_TAG_BLOCKLIST = setOf(
    "masterpiece",
    "best quality",
    "amazing quality",
    "high quality",
    "normal quality",
    "low quality",
    "worst quality",
    "very aesthetic",
    "highres",
    "absurdres",
    "no text",
    "bad anatomy",
    "bad hands",
    "bad feet",
    "extra digits",
    "fewer digits",
    "text",
    "logo",
    "signature",
    "username",
    "watermark",
)

private fun isGeneratedTagBlocked(key: String): Boolean =
    key in GENERATED_TAG_BLOCKLIST || key.startsWith("artist:") || key.startsWith("artist ")

private fun promptTagKey(tag: String): String = tag
    .lowercase()
    .removeWeightSyntax()
    .replace('_', ' ')
    .replace(Regex("\\s+"), " ")
    .trim()

private fun promptTagWeight(tag: String): Int {
    val braces = tag.count { it == '{' } - tag.count { it == '[' }
    val naiWeight = Regex("^(-?\\d+(?:\\.\\d+)?)::").find(tag)
        ?.groupValues?.getOrNull(1)?.toFloatOrNull()
        ?: 0f
    return braces * 1000 + (naiWeight * 100).toInt()
}

private fun String.removeWeightSyntax(): String {
    var value = trim()
    while (value.length >= 2 &&
        ((value.first() == '{' && value.last() == '}') ||
            (value.first() == '[' && value.last() == ']'))
    ) {
        value = value.substring(1, value.length - 1).trim()
    }
    value = value.replace(Regex("^-?\\d+(?:\\.\\d+)?::"), "")
    value = value.replace(Regex("::$"), "")
    return value.trim()
}

/** Float 输出去掉多余的尾随零（5.0 → 5, 0.5 → 0.5）。 */
private fun formatNumber(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

/**
 * 查询参数编码：保留当前工作模板中已被验证可用的字符（逗号、冒号、括号等），
 * 其余按 UTF-8 百分号编码（空格 → %20，而非表单的 +）。
 */
internal fun encodeQuery(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (b in raw.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt().toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in ALLOWED_QUERY_CHARS) {
            sb.append(c)
        } else {
            sb.append('%')
            sb.append(HEX[(b.toInt() shr 4) and 0xF])
            sb.append(HEX[b.toInt() and 0xF])
        }
    }
    return sb.toString()
}

private const val ALLOWED_QUERY_CHARS = "-_.~,:;()[]!'*@"
private const val HEX = "0123456789ABCDEF"

/**
 * 提取正文中占位符对应的稳定 URL（含起始下标，按出现顺序），
 * 供会话图片 URL 收集、渲染层与后台预取使用同一身份；代码块内忽略。
 * 稳定 URL 只依赖占位符文本与 [baseUrl]，与画师预设/参数/token 无关。
 */
fun extractAuroraStableUrlMatches(
    text: String,
    baseUrl: String,
): List<Pair<Int, String>> {
    if (!text.contains(AuroraDrawProtocol.PLACEHOLDER_HEAD)) return emptyList()
    val codeRanges = fencedCodeRanges(text)
    return AuroraDrawProtocol.DRAW_REGEX.findAll(text)
        .filter { m -> codeRanges.none { m.range.first in it } }
        .map { m ->
            val placeholder = AuroraDrawProtocol.matchToPlaceholder(m)
            m.range.first to buildAuroraStableUrl(
                tag = placeholder.tag,
                size = placeholder.size,
                seed = placeholder.seed,
                baseUrl = baseUrl,
            )
        }
        .toList()
}

/**
 * 注入系统提示的绘图协议文本（参考 misskey AGENT_IMAGE_WORLD_PROMPT 改写），
 * 图片数量区间来自配置。
 */
fun buildAuroraDrawPrompt(config: AuroraImageConfig): String {
    val min = config.imageCountMin.coerceAtLeast(1)
    val max = maxOf(config.imageCountMax, min)
    return """
        Aurora image generation protocol:
        - Insert images only where they genuinely improve the reply; generate $min-$max images per reply and never produce filler scene images.
        - Output exactly this placeholder inline in the reply text, never inside code blocks: [[aurora_draw size=<portrait|landscape|square> tag=<positive Danbooru tags>]]
        - tag contains only positive visual content tags in English (character appearance, action, composition, scene, lighting), comma separated. Prefer canonical Danbooru tags such as silver hair and looking at viewer instead of prose sentences.
        - Keep each image concise and specific: use at most 120 tags, avoid synonyms and repetitions, and do not invent uncertain tags.
        - Do not include artist names, quality tags, negative tags, model names, tokens, URLs, HTML or Markdown in tag; these are added separately by the image preset.
        - Choose size yourself: portrait for single-character portraits and full-body shots, landscape for interaction or wide scenes, square for avatar-like close-ups.
        - Organize tags in this order: character count and identity, core appearance, pose/action, composition, outfit, expression/gaze, environment, lighting, final visual details.
        - Each visible character should have count/gender, eye color, hair color/style, skin tone or notable feature, body shape when relevant, and outfit.
        - Each image should include a camera distance or composition tag such as close-up, bust shot, upper body, cowboy shot, full body, wide shot, from side, from above, or dynamic angle.
        - Write the placeholder directly between paragraphs; it will be replaced by the generated image automatically.
    """.trimIndent().trim()
}

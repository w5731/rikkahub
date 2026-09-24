package me.rerere.rikkahub.data.aurora

import me.rerere.rikkahub.data.model.AuroraArtistPreset
import me.rerere.rikkahub.data.model.AuroraImageConfig
import me.rerere.rikkahub.data.model.AuroraGenerationParams
import me.rerere.rikkahub.data.model.AuroraToken
import me.rerere.rikkahub.data.model.DEFAULT_ARTIST_PRESETS
import me.rerere.rikkahub.data.model.withNai45FullDefaults
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraDrawProtocolTest {

    // ---- 占位符解析 ----

    @Test
    fun `parse full placeholder with size`() {
        val text = "前文\n[[aurora_draw size=landscape tag=1girl, silver hair, upper body]]\n后文"
        val list = AuroraDrawProtocol.parsePlaceholders(text)
        assertEquals(1, list.size)
        assertEquals(AuroraImageSize.LANDSCAPE, list[0].size)
        assertEquals("1girl, silver hair, upper body", list[0].tag)
    }

    @Test
    fun `parse placeholder without size defaults to portrait`() {
        val list = AuroraDrawProtocol.parsePlaceholders("[[aurora_draw tag=2girls, holding hands]]")
        assertEquals(1, list.size)
        assertEquals(AuroraImageSize.PORTRAIT, list[0].size)
        assertEquals("2girls, holding hands", list[0].tag)
    }

    @Test
    fun `parse collapses multiline tag whitespace`() {
        val list = AuroraDrawProtocol.parsePlaceholders(
            "[[aurora_draw size=square tag=1girl,\n  blue eyes,\n  smile]]"
        )
        assertEquals("1girl, blue eyes, smile", list[0].tag)
    }

    @Test
    fun `parse truncates oversized stored tag without rewriting it`() {
        val longTag = "a".repeat(5000)
        val list = AuroraDrawProtocol.parsePlaceholders("[[aurora_draw seed=7 tag=$longTag]]")
        assertEquals(4000, list[0].tag.length)
    }

    @Test
    fun `stamp normalizes generated tags and removes preset-owned tags`() {
        val stamped = AuroraDrawProtocol.stampPlaceholders(
            "[[aurora_draw tag=1girl, silver_hair, silver hair, {blue eyes}, " +
                "artist:test, masterpiece, bad anatomy, 中文标签, https://example.com, smile.]]",
            "msg-id",
        )
        assertEquals("1girl,silver_hair,{blue eyes},smile", AuroraDrawProtocol.parsePlaceholders(stamped).single().tag)
    }

    @Test
    fun `stamp keeps stronger duplicate weight`() {
        val stamped = AuroraDrawProtocol.stampPlaceholders(
            "[[aurora_draw tag=blue eyes, {blue_eyes}, [smile], smile]]",
            "msg-id",
        )
        assertEquals("{blue_eyes},smile", AuroraDrawProtocol.parsePlaceholders(stamped).single().tag)
    }

    @Test
    fun `stamp limits new placeholders to prompt budget`() {
        val tags = (1..150).joinToString(",") { "tag$it" }
        val stamped = AuroraDrawProtocol.stampPlaceholders("[[aurora_draw tag=$tags]]", "msg-id")
        assertEquals(120, AuroraDrawProtocol.parsePlaceholders(stamped).single().tag.split(',').size)
    }

    @Test
    fun `stamp does not rewrite seeded historical tag`() {
        val stored = "[[aurora_draw seed=7 size=square tag=masterpiece, 中文标签, 1girl]]"
        assertEquals(stored, AuroraDrawProtocol.stampPlaceholders(stored, "msg-id"))
    }

    @Test
    fun `parse ignores non placeholder text`() {
        val text = "普通文本 [[aurora]] [[agent_draw tag=x]]"
        assertTrue(AuroraDrawProtocol.parsePlaceholders(text).isEmpty())
        assertFalse(AuroraDrawProtocol.hasPlaceholder(text))
    }

    @Test
    fun `lenient regex matches bare placeholder`() {
        // 严格解析不认，但清理历史残留的宽松正则要认
        assertTrue(AuroraDrawProtocol.parsePlaceholders("[[aurora_draw]]").isEmpty())
        assertTrue(AuroraDrawProtocol.hasPlaceholder("[[aurora_draw]]"))
    }

    @Test
    fun `parse placeholder with seed`() {
        val list = AuroraDrawProtocol.parsePlaceholders(
            "[[aurora_draw seed=123456789 size=square tag=1girl]]"
        )
        assertEquals(1, list.size)
        assertEquals(123456789L, list[0].seed)
        assertEquals(AuroraImageSize.SQUARE, list[0].size)
        assertEquals("1girl", list[0].tag)
    }

    // ---- seed 盖印 ----

    @Test
    fun `stamp placeholders writes deterministic seeds`() {
        val text = "a [[aurora_draw tag=x]] b [[aurora_draw size=square tag=y]] c"
        val stamped1 = AuroraDrawProtocol.stampPlaceholders(text, "msg-id-1")
        val stamped2 = AuroraDrawProtocol.stampPlaceholders(text, "msg-id-1")
        assertEquals(stamped1, stamped2) // 同一消息同一文本 → 相同 seed

        val placeholders = AuroraDrawProtocol.parsePlaceholders(stamped1)
        assertEquals(2, placeholders.size)
        assertTrue(placeholders.all { it.seed != null })
        assertTrue(placeholders[0].seed != placeholders[1].seed) // 同消息内不同占位符不同 seed
        assertEquals("x", placeholders[0].tag)
        assertEquals("y", placeholders[1].tag)

        // 不同消息 → 不同 seed（重新生成的分支不会复用旧图身份）
        val stamped3 = AuroraDrawProtocol.stampPlaceholders(text, "msg-id-2")
        assertTrue(
            AuroraDrawProtocol.parsePlaceholders(stamped3)[0].seed != placeholders[0].seed
        )
    }

    @Test
    fun `stamp placeholders is idempotent and keeps existing seeds`() {
        val text = "[[aurora_draw seed=42 tag=x]] 与 [[aurora_draw tag=y]]"
        val stamped = AuroraDrawProtocol.stampPlaceholders(text, "msg-id")
        assertTrue(stamped.contains("seed=42")) // 已有 seed 保留
        assertEquals(stamped, AuroraDrawProtocol.stampPlaceholders(stamped, "msg-id"))
    }

    @Test
    fun `stamp placeholders leaves plain text untouched`() {
        val text = "没有占位符的内容"
        assertEquals(text, AuroraDrawProtocol.stampPlaceholders(text, "msg-id"))
    }

    @Test
    fun `strip removes all placeholders`() {
        val text = "a [[aurora_draw tag=x]] b [[aurora_draw size=square tag=y]] c"
        assertEquals("a  b  c", AuroraDrawProtocol.stripPlaceholders(text))
    }

    // ---- 内容切分与代码块保护 ----

    @Test
    fun `split content into text and draw segments`() {
        val segments = splitAuroraDrawContent(
            "第一段\n[[aurora_draw size=portrait tag=1girl, smile]]\n第二段 [[aurora_draw tag=cat]] 结尾"
        )
        assertEquals(5, segments.size)
        assertEquals("第一段\n", (segments[0] as AuroraDrawSegment.Text).text)
        assertTrue(segments[1] is AuroraDrawSegment.Draw)
        assertEquals("\n第二段 ", (segments[2] as AuroraDrawSegment.Text).text)
        val draw = segments[3] as AuroraDrawSegment.Draw
        assertEquals("cat", draw.placeholder.tag)
        assertEquals(" 结尾", (segments[4] as AuroraDrawSegment.Text).text)
    }

    @Test
    fun `split skips placeholders inside fenced code blocks`() {
        val text = "文本\n```\n[[aurora_draw tag=inside]]\n```\n[[aurora_draw tag=outside]]"
        val segments = splitAuroraDrawContent(text)
        val draws = segments.filterIsInstance<AuroraDrawSegment.Draw>()
        assertEquals(1, draws.size)
        assertEquals("outside", draws[0].placeholder.tag)
    }

    @Test
    fun `split treats unclosed fence as code until end`() {
        val text = "前文\n```\n[[aurora_draw tag=x]]\n仍然代码"
        val segments = splitAuroraDrawContent(text)
        assertEquals(1, segments.size)
        assertTrue(segments[0] is AuroraDrawSegment.Text)
    }

    @Test
    fun `split fast path for plain text`() {
        val segments = splitAuroraDrawContent("普通内容，没有任何占位符")
        assertEquals(1, segments.size)
        assertEquals("普通内容，没有任何占位符", (segments[0] as AuroraDrawSegment.Text).text)
    }

    // ---- URL 构建 ----

    private val baseConfig = AuroraImageConfig(
        enabled = true,
        tokens = listOf(AuroraToken(token = "tok-1234567890")),
    )

    @Test
    fun `build url contains all aurora params`() {
        val url = buildAuroraImageUrl(
            tag = "1girl, silver hair",
            size = AuroraImageSize.PORTRAIT,
            config = baseConfig,
            token = "SECRET",
        )
        assertTrue(url.startsWith("https://love.auroralove.cc/api/aurora/regex-image?"))
        assertTrue(url.contains("tag=1girl,%20silver%20hair"))
        // 默认预设（二次元插画）提供 prefix/suffix；逗号/冒号/括号保持原样（与现网模板一致）
        assertTrue(url.contains("prompt_prefix=artist:ningen_mame,artist:ciloranko,artist:sho_(sho_lwlw)"))
        assertTrue(url.contains("prompt_suffix=very%20aesthetic,masterpiece,no%20text"))
        assertTrue(url.contains("size=portrait"))
        assertTrue(url.contains("model=nai-diffusion-4-5-full"))
        assertTrue(url.contains("steps=28"))
        assertTrue(url.contains("scale=10"))
        assertTrue(url.contains("cfg_rescale=0.18"))
        assertTrue(url.contains("sampler=k_euler"))
        assertTrue(url.contains("noise_schedule=karras"))
        assertTrue(url.contains("token=SECRET"))
        assertFalse(url.contains("nocache"))
    }

    @Test
    fun `stable url contains only tag size and seed`() {
        val stable = buildAuroraStableUrl(
            tag = "1girl, silver hair",
            size = AuroraImageSize.PORTRAIT,
            seed = 123456789L,
            baseUrl = baseConfig.baseUrl,
        )
        assertEquals(
            "https://love.auroralove.cc/api/aurora/regex-image?tag=1girl,%20silver%20hair&size=portrait&nocache=123456789",
            stable,
        )
        // 身份绝不含画师串/参数/token
        assertFalse(stable.contains("prompt_prefix"))
        assertFalse(stable.contains("prompt_suffix"))
        assertFalse(stable.contains("negative"))
        assertFalse(stable.contains("model"))
        assertFalse(stable.contains("steps"))
        assertFalse(stable.contains("token"))
    }

    @Test
    fun `stable url is independent of preset and params`() {
        // bug 回归：切换画师预设/调整参数不得改变历史图片的缓存身份
        val stamped = AuroraDrawProtocol.stampPlaceholders("[[aurora_draw tag=1girl]]", "msg-id")
        val configA = baseConfig.copy(defaultPresetId = "default-anime")
        val configB = baseConfig.copy(
            defaultPresetId = "line-manga",
            params = baseConfig.params.copy(steps = 40, scale = 7f),
            defaultNegativePrompt = "other negatives",
        )
        val urlsA = extractAuroraStableUrlMatches(stamped, configA.baseUrl)
        val urlsB = extractAuroraStableUrlMatches(stamped, configB.baseUrl)
        assertEquals(1, urlsA.size)
        assertEquals(urlsA, urlsB)
        assertTrue(urlsA[0].second.contains("nocache="))
    }

    @Test
    fun `full request url starts with stable identity`() {
        val full = buildAuroraImageUrl(
            tag = "1girl",
            size = AuroraImageSize.SQUARE,
            config = baseConfig,
            token = "SECRET",
            nocache = 123L,
        )
        val stable = buildAuroraStableUrl(
            tag = "1girl",
            size = AuroraImageSize.SQUARE,
            seed = 123L,
            baseUrl = baseConfig.baseUrl,
        )
        // 完整请求 URL = 稳定身份 + 样式参数 + token
        assertTrue(full.startsWith("$stable&"))
        assertTrue(full.endsWith("&token=SECRET"))
        assertTrue(full.contains("prompt_prefix="))
    }

    @Test
    fun `preset override replaces prefix suffix and merges negative`() {
        val config = baseConfig.copy(
            artistPresets = listOf(
                AuroraArtistPreset(
                    id = "p1",
                    name = "测试",
                    promptPrefix = "artist:test",
                    promptSuffix = "quality words",
                    negativePrompt = "bad anatomy, text",
                )
            ),
            defaultNegativePrompt = "worst quality, bad anatomy",
        )
        val url = buildAuroraImageUrl(
            tag = "x",
            size = AuroraImageSize.SQUARE,
            config = config,
            presetId = "p1",
        )
        assertTrue(url.contains("prompt_prefix=artist:test"))
        assertTrue(url.contains("prompt_suffix=quality%20words"))
        // 去重合并：bad anatomy 只出现一次，顺序保留
        val negativeParam = Regex("negative=([^&]*)").find(url)!!.groupValues[1]
        val decoded = negativeParam.replace("%20", " ")
        assertEquals("worst quality,bad anatomy,text", decoded)
    }

    @Test
    fun `custom prompt segments preserve user syntax while negatives deduplicate`() {
        val config = baseConfig.copy(
            artistPresets = listOf(
                AuroraArtistPreset(
                    id = "p1",
                    name = "测试",
                    promptPrefix = "artist:test, artist:test, cinematic lighting",
                    promptSuffix = "very aesthetic, masterpiece, very_aesthetic",
                    negativePrompt = "logo, lowres",
                )
            ),
            defaultPresetId = "p1",
            defaultNegativePrompt = "lowres, logo, bad quality",
        )
        val url = buildAuroraImageUrl("x", AuroraImageSize.SQUARE, config)
        assertTrue(url.contains("prompt_prefix=artist:test,%20artist:test,%20cinematic%20lighting"))
        assertTrue(url.contains("prompt_suffix=very%20aesthetic,%20masterpiece,%20very_aesthetic"))
        assertTrue(url.contains("negative=lowres,logo,bad%20quality"))
    }

    @Test
    fun `unknown preset falls back to global default preset`() {
        val url = buildAuroraImageUrl(
            tag = "x",
            size = AuroraImageSize.SQUARE,
            config = baseConfig,
            presetId = "does-not-exist",
        )
        assertTrue(url.contains("ningen_mame"))
    }

    @Test
    fun `params are sanitized to valid ranges`() {
        val config = baseConfig.copy(
            params = baseConfig.params.copy(steps = 500, scale = -3f, cfgRescale = 9f)
        )
        val url = buildAuroraImageUrl("x", AuroraImageSize.SQUARE, config)
        assertTrue(url.contains("steps=80"))
        assertTrue(url.contains("scale=0"))
        assertTrue(url.contains("cfg_rescale=1"))
    }

    // ---- 请求 URL 解析 ----

    @Test
    fun `resolve request url attaches style params and token for aurora urls`() {
        val config = AuroraImageConfig(
            enabled = true,
            baseUrl = "https://example.com",
            tokens = listOf(AuroraToken(token = "ABC")),
        )
        val stable = buildAuroraStableUrl("t", AuroraImageSize.SQUARE, seed = 7L, baseUrl = config.baseUrl)
        assertTrue(stable.startsWith("https://example.com/api/aurora/regex-image?"))

        val request = resolveAuroraRequestUrl(stable, config)
        assertTrue(request != null && request.startsWith("$stable&"))
        assertTrue(request != null && request.endsWith("&token=ABC"))
        // 请求时附加当前画师串与生成参数
        assertTrue(request!!.contains("prompt_prefix="))
        assertTrue(request.contains("model=nai-diffusion-4-5-full"))
        assertTrue(request.contains("steps=28"))
        assertTrue(request.contains("scale=10"))
        assertTrue(request.contains("cfg_rescale=0.18"))

        // 预设覆盖只影响请求 URL：换一个预设，prefix 随之变化
        val custom = config.copy(
            artistPresets = listOf(
                AuroraArtistPreset(id = "p9", name = "x", promptPrefix = "artist:nine")
            ),
        )
        val withPreset = resolveAuroraRequestUrl(stable, custom, presetId = "p9")
        assertTrue(withPreset!!.contains("prompt_prefix=artist:nine"))
        assertTrue(withPreset.startsWith("$stable&")) // 身份不变

        assertNull(resolveAuroraRequestUrl("https://other.com/img.png", config))
        assertNull(resolveAuroraRequestUrl(stable, config.copy(enabled = false)))
        assertNull(resolveAuroraRequestUrl(stable, config.copy(tokens = emptyList())))
    }

    @Test
    fun `extract stable url matches in order`() {
        val text = "a ![alt](https://x.com/1.png) b [[aurora_draw tag=girl]] c"
        val matches = extractAuroraStableUrlMatches(text, baseConfig.baseUrl)
        assertEquals(1, matches.size)
        assertTrue(matches[0].second.startsWith("https://love.auroralove.cc/api/aurora/regex-image?tag=girl"))
    }

    // ---- 旧版身份迁移 ----

    @Test
    fun `legacy config json preserves every stored field`() {
        val json = """
            {
              "enabled": true,
              "baseUrl": "https://legacy.example",
              "tokens": [{"id":"token-id","token":"secret","name":"old","enabled":false,"points":7,"lastCheckedAt":123}],
              "defaultModel": "nai-diffusion-4-5-full",
              "params": {"steps":28,"scale":5.0,"cfgRescale":0.0,"sampler":"k_euler_ancestral","noiseSchedule":"karras"},
              "defaultNegativePrompt": "custom negative",
              "artistPresets": [{"id":"custom","name":"自定义","promptPrefix":"[artist:test]","promptSuffix":"old quality","negativePrompt":"old negative"}],
              "defaultPresetId": "custom",
              "imageCountMin": 2,
              "imageCountMax": 4,
              "futureField": "ignored"
            }
        """.trimIndent()
        val config = JsonInstant.decodeFromString<AuroraImageConfig>(json)
        assertTrue(config.enabled)
        assertEquals("https://legacy.example", config.baseUrl)
        assertEquals("secret", config.tokens.single().token)
        assertFalse(config.tokens.single().enabled)
        assertEquals(7, config.tokens.single().points)
        assertEquals(123L, config.tokens.single().lastCheckedAt)
        assertEquals(5f, config.params.scale)
        assertEquals(0f, config.params.cfgRescale)
        assertEquals("k_euler_ancestral", config.params.sampler)
        assertEquals("custom negative", config.defaultNegativePrompt)
        assertEquals("[artist:test]", config.artistPresets.single().promptPrefix)
        assertEquals("old quality", config.artistPresets.single().promptSuffix)
        assertEquals("old negative", config.artistPresets.single().negativePrompt)
        assertEquals("custom", config.defaultPresetId)
        assertEquals(2, config.imageCountMin)
        assertEquals(4, config.imageCountMax)
    }

    @Test
    fun `legacy stable url keeps first-version param order`() {
        val legacy = buildLegacyAuroraStableUrl("1girl", AuroraImageSize.PORTRAIT, baseConfig)
        assertTrue(legacy.startsWith("https://love.auroralove.cc/api/aurora/regex-image?"))
        // 首版身份参数顺序契约：迁移 rekey 依赖该顺序复原旧缓存键
        val order = listOf("tag=", "prompt_prefix=", "prompt_suffix=", "negative=", "size=", "model=", "steps=", "scale=", "cfg_rescale=", "sampler=", "noise_schedule=")
        val positions = order.map { legacy.indexOf(it) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        assertFalse(legacy.contains("token="))
        assertFalse(legacy.contains("nocache="))
    }

    @Test
    fun `legacy url remains byte compatible with first version defaults`() {
        val legacyConfig = AuroraImageConfig(
            params = AuroraGenerationParams(
                steps = 28,
                scale = 5f,
                cfgRescale = 0f,
                sampler = "k_euler_ancestral",
                noiseSchedule = "karras",
            ),
            defaultNegativePrompt = "bad anatomy, bad hands",
            artistPresets = listOf(
                AuroraArtistPreset(
                    id = "legacy",
                    name = "旧预设",
                    promptPrefix = "[artist:test], artist:other",
                    promptSuffix = "masterpiece,best quality,very aesthetic,highres,absurdres",
                    negativePrompt = "bad hands, text",
                )
            ),
            defaultPresetId = "legacy",
        )
        val url = buildLegacyAuroraStableUrl(
            tag = "1girl, silver hair",
            size = AuroraImageSize.PORTRAIT,
            config = legacyConfig,
        )
        assertEquals(
            "https://love.auroralove.cc/api/aurora/regex-image?" +
                "tag=1girl,%20silver%20hair&" +
                "prompt_prefix=[artist:test],%20artist:other&" +
                "prompt_suffix=masterpiece,best%20quality,very%20aesthetic,highres,absurdres&" +
                "negative=bad%20anatomy,bad%20hands,text&size=portrait&" +
                "model=nai-diffusion-4-5-full&steps=28&scale=5&cfg_rescale=0&" +
                "sampler=k_euler_ancestral&noise_schedule=karras",
            url,
        )
    }

    @Test
    fun `legacy stamp preserves tags and placeholder order for migration rekey`() {
        val before =
            "a [[aurora_draw tag=masterpiece, 中文标签, silver_hair]] " +
                "b [[aurora_draw size=square tag={blue eyes}, artist:test]]"
        val after = AuroraDrawProtocol.stampLegacyPlaceholders(before, "msg-id")
        val legacy = AuroraDrawProtocol.parsePlaceholders(before)
        val stamped = AuroraDrawProtocol.parsePlaceholders(after)
        assertEquals(legacy.size, stamped.size)
        legacy.zip(stamped).forEach { (l, s) ->
            assertEquals(l.tag, s.tag)
            assertEquals(l.size, s.size)
            assertTrue(s.seed != null)
        }
        assertTrue(after.contains("tag=masterpiece, 中文标签, silver_hair]]"))
        assertTrue(after.contains("tag={blue eyes}, artist:test]]"))
    }

    // ---- 推荐配置手动恢复 ----

    @Test
    fun `nai 45 reset keeps artist presets`() {
        val customPresets = listOf(AuroraArtistPreset("custom", "自定义", "artist:test"))
        val reset = AuroraImageConfig(
            defaultModel = "other-model",
            params = AuroraGenerationParams(scale = 3f),
            defaultNegativePrompt = "other",
            artistPresets = customPresets,
        ).withNai45FullDefaults()
        assertEquals(AuroraImageConfig.DEFAULT_MODEL, reset.defaultModel)
        assertEquals(AuroraGenerationParams(), reset.params)
        assertEquals(AuroraImageConfig.DEFAULT_NEGATIVE_PROMPT, reset.defaultNegativePrompt)
        assertEquals(customPresets, reset.artistPresets)
        assertEquals(DEFAULT_ARTIST_PRESETS.size, AuroraImageConfig().resolvePresets().size)
    }

    // ---- 协议提示 ----

    @Test
    fun `protocol prompt contains syntax and count`() {
        val prompt = buildAuroraDrawPrompt(baseConfig)
        assertTrue(prompt.contains("[[aurora_draw size=<portrait|landscape|square> tag=<positive Danbooru tags>]]"))
        assertTrue(prompt.contains("generate 5-8 images"))
        assertTrue(prompt.contains("at most 120 tags"))
        assertTrue(prompt.contains("added separately by the image preset"))
    }
}

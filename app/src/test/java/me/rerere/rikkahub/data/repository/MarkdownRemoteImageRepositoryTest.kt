package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownRemoteImageRepositoryTest {
    @Test
    fun `query parser returns null for malformed percent escape`() {
        val url = "https://example.com/api/aurora/regex-image?tag=1girl%,bad&size=square&nocache=7"
        assertNull(MarkdownRemoteImageRepository.parseQueryParameters(url))
        assertNull(MarkdownRemoteImageRepository.auroraStableIdentity(url))
    }

    @Test
    fun `query parser decodes valid aurora identity`() {
        val url = "https://example.com/api/aurora/regex-image?tag=1girl,%20blue%20eyes&size=square&nocache=7"
        assertEquals(
            Triple("1girl, blue eyes", "square", "7"),
            MarkdownRemoteImageRepository.auroraStableIdentity(url),
        )
    }
}

package me.rerere.rikkahub.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.asDrawable
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.MarkdownRemoteImageDAO
import me.rerere.rikkahub.data.db.entity.MarkdownRemoteImageEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.utils.isRemoteHttpUrl
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * 远端 Markdown 图片：Room 元数据 + filesDir 下 PNG 副本。
 *
 * 「刷新」语义：仅在网络解码成功之后才覆盖本地文件；失败时保留原有本地副本。
 */
class MarkdownRemoteImageRepository(
    private val context: Context,
    private val dao: MarkdownRemoteImageDAO,
) {
    private val _imageUpdates = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val imageUpdates = _imageUpdates.asSharedFlow()

    fun observeAll(): Flow<List<MarkdownRemoteImageEntity>> = dao.observeAll()

    suspend fun getBySourceUrl(sourceUrl: String): MarkdownRemoteImageEntity? =
        withContext(Dispatchers.IO) {
            val id = sha256Hex(sourceUrl)
            val exactRow = dao.getById(id)
            if (exactRow != null && resolveAbsoluteFile(exactRow).isFile) return@withContext exactRow

            if (sourceUrl.contains("/api/aurora/regex-image?")) {
                val identity = auroraStableIdentity(sourceUrl)
                val matched = identity?.let { target ->
                    dao.getAuroraImages()
                        .filter { entity ->
                            auroraStableIdentity(entity.sourceUrl) == target && resolveAbsoluteFile(entity).isFile
                        }
                        .singleOrNull()
                }
                if (matched != null) return@withContext matched
            }

            val relativePath = "${FileFolders.MD_REMOTE_IMAGES}/$id.png"
            val file = File(context.filesDir, relativePath)
            if (!file.isFile) return@withContext null

            val recovered = MarkdownRemoteImageEntity(
                id = id,
                sourceUrl = sourceUrl,
                relativePath = relativePath,
                createdAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
            dao.insert(recovered)
            recovered
        }

    fun resolveAbsoluteFile(entity: MarkdownRemoteImageEntity): File =
        File(context.filesDir, entity.relativePath)

    fun fileExists(entity: MarkdownRemoteImageEntity): Boolean =
        resolveAbsoluteFile(entity).isFile

    /** 读取本地 PNG/JPEG 像素尺寸（不解码全图）；无法读取时返回 null。 */
    fun decodeBitmapSizeFromFile(file: File): Pair<Int, Int>? {
        if (!file.isFile) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    /**
     * 首次展示成功后持久化（若本地已有副本则不再写入）。
     */
    suspend fun persistDecodedBitmap(sourceUrl: String, bitmap: Bitmap): Uri? =
        withContext(Dispatchers.IO) {
            val id = sha256Hex(sourceUrl)
            val existing = dao.getById(id)
            if (existing != null) {
                val f = resolveAbsoluteFile(existing)
                if (f.isFile) return@withContext f.toUri()
            }

            val safeBitmap = ensureSoftwareBitmap(bitmap)
            val pngBytes = FileUtils.compressBitmapToPng(safeBitmap)
            val relativePath = "${FileFolders.MD_REMOTE_IMAGES}/$id.png"
            val outFile = File(context.filesDir, relativePath)
            outFile.parentFile?.mkdirs()
            writeBytesByReplacing(outFile, pngBytes)

            val entity = MarkdownRemoteImageEntity(
                id = id,
                sourceUrl = sourceUrl,
                relativePath = relativePath,
                createdAt = System.currentTimeMillis(),
            )
            dao.insert(entity)
            outFile.toUri()
        }

    suspend fun deleteById(id: String): Boolean = withContext(Dispatchers.IO) {
        val row = dao.getById(id) ?: return@withContext false
        val f = resolveAbsoluteFile(row)
        if (f.exists()) {
            f.delete()
        }
        dao.deleteById(id) > 0
    }

    suspend fun findLegacyAuroraSourceUrl(
        tag: String,
        size: String,
        preferredSourceUrl: String,
    ): String? = withContext(Dispatchers.IO) {
        val rows = dao.getAuroraImages().filter { resolveAbsoluteFile(it).isFile }
        rows.firstOrNull { it.sourceUrl == preferredSourceUrl }?.sourceUrl
            ?: rows.filter { entity ->
                val query = parseQueryParameters(entity.sourceUrl) ?: return@filter false
                "prompt_prefix" in query && query["tag"] == tag && query["size"] == size
            }.singleOrNull()?.sourceUrl
    }

    /**
     * 迁移用：把旧身份 URL 的本地副本复制到新身份 URL 名下（旧记录保留）。
     * 新身份已有副本或旧副本不存在时跳过；返回是否发生了复制。
     */
    suspend fun rekeySourceUrl(oldSourceUrl: String, newSourceUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            if (oldSourceUrl == newSourceUrl) return@withContext false
            val newId = sha256Hex(newSourceUrl)
            val newRelativePath = "${FileFolders.MD_REMOTE_IMAGES}/$newId.png"
            val newFile = File(context.filesDir, newRelativePath)
            val existingNewRow = dao.getById(newId)
            if (newFile.isFile) {
                if (existingNewRow == null) {
                    dao.insert(
                        MarkdownRemoteImageEntity(
                            id = newId,
                            sourceUrl = newSourceUrl,
                            relativePath = newRelativePath,
                            createdAt = newFile.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                        )
                    )
                }
                return@withContext false
            }

            val oldId = sha256Hex(oldSourceUrl)
            val oldRow = dao.getById(oldId)
            val oldFile = oldRow?.let(::resolveAbsoluteFile)
                ?: File(context.filesDir, "${FileFolders.MD_REMOTE_IMAGES}/$oldId.png")
            if (!oldFile.isFile) return@withContext false

            val relativePath = newRelativePath
            val outFile = newFile
            outFile.parentFile?.mkdirs()
            oldFile.copyTo(outFile, overwrite = true)
            dao.insert(
                MarkdownRemoteImageEntity(
                    id = newId,
                    sourceUrl = newSourceUrl,
                    relativePath = relativePath,
                    createdAt = oldRow?.createdAt
                        ?: oldFile.lastModified().takeIf { it > 0 }
                        ?: System.currentTimeMillis(),
                )
            )
            true
        }

    suspend fun previewModelForSourceUrl(sourceUrl: String): String = withContext(Dispatchers.IO) {
        if (!sourceUrl.isRemoteHttpUrl()) return@withContext sourceUrl
        val row = getBySourceUrl(sourceUrl) ?: return@withContext sourceUrl
        val f = resolveAbsoluteFile(row)
        if (f.isFile) f.toUri().toString() else sourceUrl
    }

    suspend fun previewModelsForUrls(urls: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            if (urls.isEmpty()) return@withContext emptyList()

            // 把所有 http(s) URL 的查询合并成一次 SQL，避免 N 次往返带来的 100~1000ms 卡顿。
            val httpUrls = urls.filter { it.isRemoteHttpUrl() }
            val idToUrl = httpUrls.associateBy { sha256Hex(it) }
            val rowById = buildMap {
                if (idToUrl.isNotEmpty()) {
                    putAll(dao.getByIds(idToUrl.keys.toList()).associateBy { it.id })
                    idToUrl.forEach { (id, url) ->
                        if (id !in this) getBySourceUrl(url)?.let { put(id, it) }
                    }
                }
            }
            urls.map { u ->
                if (!u.isRemoteHttpUrl()) {
                    u
                } else {
                    val row = rowById[sha256Hex(u)]
                    if (row != null) {
                        val f = resolveAbsoluteFile(row)
                        if (f.isFile) f.toUri().toString() else u
                    } else {
                        u
                    }
                }
            }
        }

    /**
     * 仅在本地无副本时从网络拉取并落盘（后台预取用）；已有副本直接返回本地 Uri，
     * 网络失败返回 null（保留「未生图」占位，等待用户手动刷新）。
     */
    suspend fun fetchIfMissing(
        imageLoader: ImageLoader,
        sourceUrl: String,
        requestUrl: String? = null,
        timeoutMs: Int? = null,
    ): Uri? = withContext(Dispatchers.IO) {
        require(sourceUrl.isRemoteHttpUrl()) { "remote fetch requires http(s) url" }
        getBySourceUrl(sourceUrl)?.let { existing ->
            val file = resolveAbsoluteFile(existing)
            if (file.isFile) return@withContext file.toUri()
        }
        val bitmap = runCatching {
            fetchBitmapFromRemote(imageLoader, requestUrl ?: sourceUrl, timeoutMs)
        }.getOrNull() ?: return@withContext null
        persistDecodedBitmap(sourceUrl, bitmap)
    }

    /**
     * 从网络重新拉取并解码；**仅在成功后**覆盖本地 PNG 与数据库记录。
     * 失败时不改动现有本地文件。
     *
     * @param sourceUrl 缓存身份（落盘键基于它计算）
     * @param requestUrl 实际请求的 URL；与身份不同时用于附带鉴权参数等（如艾罗拉 token）
     * @param appendNocache 为 true 时在请求 URL 上追加 nocache 时间戳（重新生成新图）
     * @param timeoutMs HTTP 兜底路径的超时；生图类接口耗时可达数分钟，需要调大
     */
    suspend fun refreshFromNetwork(
        imageLoader: ImageLoader,
        sourceUrl: String,
        requestUrl: String? = null,
        appendNocache: Boolean = false,
        timeoutMs: Int? = null,
    ): Uri = withContext(Dispatchers.IO) {
        require(sourceUrl.isRemoteHttpUrl()) { "remote refresh requires http(s) url" }
        var effectiveUrl = requestUrl ?: sourceUrl
        if (appendNocache) {
            effectiveUrl = withNocacheParam(effectiveUrl)
        }
        val bitmap = fetchBitmapFromRemote(imageLoader, effectiveUrl, timeoutMs)
        overwritePersistedRemoteImage(sourceUrl, bitmap).also {
            _imageUpdates.tryEmit(sourceUrl)
        }
    }

    private suspend fun fetchBitmapFromRemote(
        imageLoader: ImageLoader,
        requestUrl: String,
        timeoutMs: Int? = null,
    ): Bitmap {
        val request = ImageRequest.Builder(context)
            .data(requestUrl)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .httpHeaders(refreshNetworkHeaders())
            .allowHardware(false)
            .build()
        return when (val result = imageLoader.execute(request)) {
            is SuccessResult -> {
                val drawable = result.image.asDrawable(context.resources)
                drawable.toBitmap()
            }
            is ErrorResult -> {
                decodeBitmapViaHttp(requestUrl, timeoutMs)
                    ?: throw result.throwable
            }
        }
    }

    /** 与 [persistDecodedBitmap] 不同：总是重写磁盘文件并 REPLACE 写入数据库（用于刷新成功后覆盖）。 */
    private suspend fun overwritePersistedRemoteImage(sourceUrl: String, bitmap: Bitmap): Uri {
        val id = sha256Hex(sourceUrl)
        val safeBitmap = ensureSoftwareBitmap(bitmap)
        val pngBytes = FileUtils.compressBitmapToPng(safeBitmap)
        val relativePath = "${FileFolders.MD_REMOTE_IMAGES}/$id.png"
        val outFile = File(context.filesDir, relativePath)
        outFile.parentFile?.mkdirs()
        writeBytesByReplacing(outFile, pngBytes)

        val entity = MarkdownRemoteImageEntity(
            id = id,
            sourceUrl = sourceUrl,
            relativePath = relativePath,
            createdAt = System.currentTimeMillis(),
        )
        dao.insert(entity)
        return outFile.toUri()
    }

    private fun writeBytesByReplacing(outFile: File, bytes: ByteArray) {
        val tmpFile = File(outFile.parentFile, "${outFile.name}.tmp")
        tmpFile.writeBytes(bytes)
        if (!tmpFile.renameTo(outFile)) {
            if (outFile.exists() && !outFile.delete()) {
                tmpFile.delete()
                throw IOException("Failed to replace image file: ${outFile.absolutePath}")
            }
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.delete()
                throw IOException("Failed to move image file: ${outFile.absolutePath}")
            }
        }
    }

    private fun refreshNetworkHeaders(): NetworkHeaders =
        NetworkHeaders.Builder()
            .set("User-Agent", RefreshUserAgent)
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .build()

    private fun decodeBitmapViaHttp(sourceUrl: String, timeoutMs: Int? = null): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(sourceUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs ?: 25_000
            conn.readTimeout = timeoutMs ?: 25_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", RefreshUserAgent)
            conn.setRequestProperty("Accept", "image/*,*/*;q=0.8")
            val code = conn.responseCode
            if (code !in 200..299) return null
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun ensureSoftwareBitmap(bitmap: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        return bitmap
    }

    companion object {
        private const val RefreshUserAgent: String =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        /** 追加（或替换）nocache 查询参数作为缓存穿透。 */
        private fun withNocacheParam(url: String, timestamp: Long = System.currentTimeMillis()): String {
            val nocacheRegex = Regex("([?&])nocache=[^&]*")
            return if (nocacheRegex.containsMatchIn(url)) {
                url.replace(nocacheRegex, "$1nocache=$timestamp")
            } else {
                val sep = if (url.contains('?')) '&' else '?'
                "$url${sep}nocache=$timestamp"
            }
        }

        internal fun auroraStableIdentity(url: String): Triple<String, String, String?>? {
            if (!url.contains("/api/aurora/regex-image?")) return null
            val query = parseQueryParameters(url) ?: return null
            val tag = query["tag"] ?: return null
            val size = query["size"] ?: return null
            return Triple(tag, size, query["nocache"])
        }

        /**
         * 解析 URL 查询参数。历史缓存中可能存在非法百分号转义的旧 URL，
         * 解码失败时返回 null（视为不可识别），绝不允许抛出异常导致界面崩溃。
         */
        internal fun parseQueryParameters(url: String): Map<String, String>? =
            runCatching {
                url.substringAfter('?', "")
                    .split('&')
                    .filter { it.isNotBlank() }
                    .associate { part ->
                        val key = part.substringBefore('=')
                        val value = part.substringAfter('=', "")
                        URLDecoder.decode(key, Charsets.UTF_8.name()) to
                            URLDecoder.decode(value, Charsets.UTF_8.name())
                    }
            }.getOrNull()

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }
}

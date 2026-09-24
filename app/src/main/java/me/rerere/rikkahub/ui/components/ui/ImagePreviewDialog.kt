package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.dokar.sonner.ToastType
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.MarkdownRemoteImageRepository
import me.rerere.rikkahub.ui.components.richtext.ChatImageNavigation
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.isRemoteHttpUrl
import org.koin.compose.koinInject
import java.io.File

private const val ErrorToastMessageMaxLength = 220

private fun Throwable.toastMessage(): String {
    val fromChain = generateSequence(this) { it.cause }
        .mapNotNull { it.message?.trim()?.takeIf { m -> m.isNotEmpty() } }
        .firstOrNull()
    if (fromChain != null) {
        return if (fromChain.length > ErrorToastMessageMaxLength) {
            fromChain.take(ErrorToastMessageMaxLength) + "…"
        } else {
            fromChain
        }
    }
    return javaClass.simpleName
}

@Composable
fun ImagePreviewDialog(
    images: List<String>,
    sourceUrls: List<String> = emptyList(),
    initialPage: Int = 0,
    /**
     * 这些下标对应的图片是「本地无图」状态，全屏不去走 Coil 加载，直接由本组件渲染破碎图标，
     * 用户可通过刷新按钮转入「请求中」。集合默认为空。
     */
    missingIndices: Set<Int> = emptySet(),
    showRefreshButton: Boolean = false,
    chatImageNavigation: ChatImageNavigation? = null,
    refreshingSourceUrls: Set<String> = emptySet(),
    /** 设置页等无 [chatImageNavigation] 时，根据 URL 全局查找会话并跳转；与聊天内定位互斥使用其一即可。 */
    onExternalLocate: ((String) -> Unit)? = null,
    /**
     * 无聊天上下文时用于填充详情里的会话标题与消息序号（返回 Pair：标题, 消息下标 0-based）；
     * 与 [onExternalLocate] 使用同一套查找时可一并传入。
     */
    resolveConversationDetailForExternalSettings: (suspend (String) -> Pair<String, Int>?)? = null,
    onRefreshConfirmed: (suspend (pageIndex: Int) -> String?)? = null,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val repo: MarkdownRemoteImageRepository = koinInject()
    val toaster = LocalToaster.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 极端情况：调用方传了空列表，直接关闭，避免 ImagePager / 状态栏访问崩溃。
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onDismissRequest() }
        return
    }

    val state = rememberZoomablePagerState { images.size }
    val displayImages = remember(images) {
        mutableStateListOf<String>().apply { addAll(images) }
    }
    val imageEpochs = remember(images) { mutableStateMapOf<Int, Int>() }
    val refreshingPages = remember(images) { mutableStateMapOf<Int, Boolean>() }
    val displayMissingPages = remember(images, missingIndices) {
        mutableStateMapOf<Int, Boolean>().apply {
            missingIndices.forEach { put(it, true) }
        }
    }

    val urlsAligned = remember(images, sourceUrls) {
        when {
            sourceUrls.size == images.size -> sourceUrls
            else -> images.map { m ->
                if (m.isRemoteHttpUrl()) m else ""
            }
        }
    }

    LaunchedEffect(refreshingSourceUrls, urlsAligned) {
        urlsAligned.forEachIndexed { index, url ->
            if (url in refreshingSourceUrls) {
                refreshingPages[index] = true
            } else if (refreshingPages[index] == true) {
                refreshingPages.remove(index)
                imageEpochs[index] = (imageEpochs[index] ?: 0) + 1
            }
        }
    }

    LaunchedEffect(images, initialPage) {
        if (images.isNotEmpty()) {
            val target = initialPage.coerceIn(0, images.lastIndex)
            state.scrollToPage(target)
        }
    }

    var pendingRefresh by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }
    val detailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var bitmapDims by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val currentPage = state.currentPage.coerceIn(0, (displayImages.size - 1).coerceAtLeast(0))
    val currentModel = displayImages.getOrNull(currentPage).orEmpty()
    val currentSourceUrl = urlsAligned.getOrNull(currentPage).orEmpty()
    val currentIsRefreshing = refreshingPages[currentPage] == true

    val resolvedForDetail = currentSourceUrl.ifBlank {
        currentModel.takeIf { it.isRemoteHttpUrl() }.orEmpty()
    }
    val msgIndexInChat = remember(resolvedForDetail, chatImageNavigation) {
        if (resolvedForDetail.isNotBlank() && chatImageNavigation != null) {
            chatImageNavigation.messageIndexForRemoteUrl(resolvedForDetail)
        } else {
            null
        }
    }

    var externalConversationDetail by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(
        resolvedForDetail,
        showDetailSheet,
        chatImageNavigation,
        resolveConversationDetailForExternalSettings,
    ) {
        externalConversationDetail = null
        if (!showDetailSheet || resolvedForDetail.isBlank()) return@LaunchedEffect
        if (chatImageNavigation != null || resolveConversationDetailForExternalSettings == null) {
            return@LaunchedEffect
        }
        externalConversationDetail = withContext(Dispatchers.IO) {
            resolveConversationDetailForExternalSettings(resolvedForDetail)
        }
    }

    val favoriteUntitled = stringResource(R.string.favorite_page_untitled_conversation)
    val externalDetailSnapshot = externalConversationDetail
    val chatTitleForDetailSheet = when {
        chatImageNavigation != null -> when {
            chatImageNavigation.conversationTitle.isNotBlank() -> chatImageNavigation.conversationTitle
            else -> favoriteUntitled
        }
        externalDetailSnapshot != null ->
            externalDetailSnapshot.first.ifBlank { favoriteUntitled }
        else -> "—"
    }
    val mergedMessageIndex = msgIndexInChat ?: externalDetailSnapshot?.second
    val messagePositionForDetailSheet = if (mergedMessageIndex != null) {
        stringResource(R.string.markdown_remote_image_detail_message_format, mergedMessageIndex + 1)
    } else {
        "—"
    }

    val showLocateInDetail =
        resolvedForDetail.isNotBlank() &&
            ((chatImageNavigation != null && msgIndexInChat != null) || onExternalLocate != null)

    LaunchedEffect(showDetailSheet, currentPage, currentModel) {
        bitmapDims = null
        if (!showDetailSheet) return@LaunchedEffect
        val dims = withContext(Dispatchers.IO) {
            val uri = runCatching { currentModel.toUri() }.getOrNull()
            val file = when {
                uri?.scheme.equals("file", ignoreCase = true) ->
                    runCatching { uri!!.toFile() }.getOrNull()
                currentModel.isNotBlank() && !currentModel.contains("://") ->
                    File(currentModel).takeIf { it.isFile }
                else -> null
            }
            if (file != null && file.isFile) repo.decodeBitmapSizeFromFile(file) else null
        }
        bitmapDims = dims
    }

    if (pendingRefresh && showRefreshButton && onRefreshConfirmed != null) {
        AlertDialog(
            onDismissRequest = { pendingRefresh = false },
            title = { Text(stringResource(R.string.markdown_remote_image_refresh_title)) },
            text = { Text(stringResource(R.string.markdown_remote_image_refresh_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRefresh = false
                        val page = state.currentPage
                        refreshingPages[page] = true
                        lifecycleOwner.lifecycleScope.launch {
                            runCatching { onRefreshConfirmed.invoke(page) }
                                .onSuccess { refreshedModel ->
                                    if (!refreshedModel.isNullOrBlank() && page in displayImages.indices) {
                                        displayImages[page] = refreshedModel
                                        displayMissingPages.remove(page)
                                    }
                                    imageEpochs[page] = (imageEpochs[page] ?: 0) + 1
                                    toaster.show(
                                        message = context.getString(R.string.markdown_remote_image_refresh_done),
                                        type = ToastType.Success,
                                    )
                                }
                                .onFailure { e ->
                                    e.printStackTrace()
                                    toaster.show(
                                        message = e.toastMessage(),
                                        type = ToastType.Error,
                                    )
                                }
                            refreshingPages.remove(page)
                        }
                    },
                ) {
                    Text(stringResource(R.string.markdown_remote_image_refresh_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRefresh = false }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            },
        )
    }

    if (showDetailSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDetailSheet = false },
            sheetState = detailSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxHeight(0.92f),
        ) {
            RemoteImageDetailContent(
                resolvedUrl = resolvedForDetail,
                displayModel = currentModel,
                bitmapDims = bitmapDims,
                chatTitleDisplay = chatTitleForDetailSheet,
                messagePositionText = messagePositionForDetailSheet,
                showLocateButton = showLocateInDetail,
                settingsHintVisible = chatImageNavigation == null && onExternalLocate == null,
                onLocateClick = {
                    if (resolvedForDetail.isNotBlank()) {
                        when {
                            chatImageNavigation != null && msgIndexInChat != null -> {
                                chatImageNavigation.locateRemoteImageUrl(resolvedForDetail)
                                showDetailSheet = false
                                onDismissRequest()
                            }
                            onExternalLocate != null -> {
                                onExternalLocate.invoke(resolvedForDetail)
                                showDetailSheet = false
                                onDismissRequest()
                            }
                            else -> Unit
                        }
                    }
                },
            )
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        SideEffect {
            // 让 Dialog 自身的背景透明、图片区域延伸到状态栏/导航栏；
            // 真正的遮罩由我们自己的根 Box 提供，避免出现透出底部 Activity 的情况。
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        // 半透明蒙版：仍能透出底下聊天，但被压暗一层。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
        ) {
            val missingPlaceholder = if (LocalDarkMode.current) {
                R.drawable.placeholder_dark
            } else {
                R.drawable.placeholder
            }
            val brokenPainter = painterResource(missingPlaceholder)
            // 跟踪每页的 Coil 加载状态，仅当前页处于 Loading 时叠加普通图片加载指示器。
            val pageLoadStates = remember(images) { mutableStateMapOf<Int, AsyncImagePainter.State>() }
            ImagePager(
                modifier = Modifier
                    .fillMaxSize(),
                pagerState = state,
                imageLoader = { index ->
                    if (displayMissingPages[index] == true && refreshingPages[index] != true) {
                        pageLoadStates[index] = AsyncImagePainter.State.Empty
                        return@ImagePager Pair(brokenPainter, brokenPainter.intrinsicSize)
                    }
                    if (refreshingPages[index] == true) {
                        pageLoadStates[index] = AsyncImagePainter.State.Empty
                        return@ImagePager Pair(brokenPainter, brokenPainter.intrinsicSize)
                    }
                    val data = displayImages[index]
                    val epoch = imageEpochs[index] ?: 0
                    val localFile = remember(data, epoch) { localFileForImageModel(data) }
                    val cacheKey = remember(data, epoch, localFile?.lastModified(), localFile?.length()) {
                        localFile?.let {
                            "${it.absolutePath}_${it.lastModified()}_${it.length()}_$epoch"
                        }
                    }
                    val req = ImageRequest.Builder(context)
                        .data(data)
                        .apply {
                            if (cacheKey != null) {
                                memoryCacheKey(cacheKey)
                                diskCacheKey(cacheKey)
                            }
                        }
                        .build()
                    val painter = rememberAsyncImagePainter(req)
                    LaunchedEffect(painter) {
                        painter.state.collect { st -> pageLoadStates[index] = st }
                    }
                    return@ImagePager Pair(painter, painter.intrinsicSize)
                },
            )

            val currentPageState = pageLoadStates[state.currentPage]
            val currentIsLoading = currentIsRefreshing ||
                currentPageState is AsyncImagePainter.State.Loading ||
                currentPageState is AsyncImagePainter.State.Empty &&
                displayMissingPages[state.currentPage] != true
            if (currentIsLoading) {
                Image(
                    painter = brokenPainter,
                    contentDescription = stringResource(R.string.markdown_remote_image_loading_cd),
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(1f)
                        .shimmer(isLoading = true),
                    contentScale = ContentScale.Fit,
                    alpha = 0.9f,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(2f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.18f),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 4.dp)
                    .zIndex(3f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showRefreshButton && onRefreshConfirmed != null) {
                    IconButton(onClick = { pendingRefresh = true }) {
                        Icon(
                            imageVector = HugeIcons.Refresh01,
                            contentDescription = stringResource(R.string.markdown_remote_image_refresh_cd),
                            tint = Color.White,
                        )
                    }
                }
                IconButton(onClick = { showDetailSheet = true }) {
                    Icon(
                        imageVector = HugeIcons.MoreVertical,
                        contentDescription = stringResource(R.string.markdown_remote_image_detail_cd),
                        tint = Color.White,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(2f)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val saveEnabled = displayMissingPages[state.currentPage] != true &&
                    displayImages.getOrNull(state.currentPage).orEmpty().isNotBlank() &&
                    refreshingPages[state.currentPage] != true
                IconButton(
                    enabled = saveEnabled,
                    onClick = {
                        val safeIndex = state.currentPage.coerceIn(0, displayImages.lastIndex)
                        val imgUrl = displayImages.getOrNull(safeIndex)
                        if (imgUrl.isNullOrBlank()) return@IconButton
                        lifecycleOwner.lifecycleScope.launch {
                            runCatching {
                                toaster.show(context.getString(R.string.markdown_remote_image_save_started))
                                filesManager.saveMessageImage(context, imgUrl)
                                toaster.show(
                                    message = context.getString(R.string.markdown_remote_image_save_done),
                                    type = ToastType.Success,
                                )
                            }.onFailure {
                                it.printStackTrace()
                                toaster.show(
                                    message = it.toastMessage(),
                                    type = ToastType.Error,
                                )
                            }
                        }
                    },
                ) {
                    Icon(
                        HugeIcons.Download01,
                        null,
                        tint = Color.White.copy(alpha = if (saveEnabled) 1f else 0.35f),
                    )
                }
            }
        }
    }
}

private fun localFileForImageModel(model: String): File? {
    val uri = runCatching { model.toUri() }.getOrNull()
    return when {
        uri?.scheme.equals("file", ignoreCase = true) ->
            runCatching { uri!!.toFile() }.getOrNull()?.takeIf { it.isFile }
        model.isNotBlank() && !model.contains("://") ->
            File(model).takeIf { it.isFile }
        else -> null
    }
}

@Composable
private fun RemoteImageDetailContent(
    resolvedUrl: String,
    displayModel: String,
    bitmapDims: Pair<Int, Int>?,
    chatTitleDisplay: String,
    messagePositionText: String,
    showLocateButton: Boolean,
    settingsHintVisible: Boolean,
    onLocateClick: () -> Unit,
) {
    val scroll = rememberScrollState()
    val urlScroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val context = LocalContext.current

    val localFile: File? = remember(displayModel) { localFileForImageModel(displayModel) }

    val storageText = when {
        localFile != null -> localFile.absolutePath
        else -> stringResource(R.string.markdown_remote_image_detail_storage_none)
    }

    val resolutionText = bitmapDims?.let { (w, h) ->
        stringResource(R.string.markdown_remote_image_detail_resolution_format, w, h)
    } ?: stringResource(R.string.markdown_remote_image_detail_resolution_unknown)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(scroll),
    ) {
        Text(
            text = stringResource(R.string.markdown_remote_image_detail_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.markdown_remote_image_detail_url),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)),
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .verticalScroll(urlScroll)
                        .padding(12.dp),
                ) {
                    Text(
                        text = resolvedUrl.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                if (resolvedUrl.isNotBlank()) {
                    clipboard.setText(AnnotatedString(resolvedUrl))
                    toaster.show(
                        message = context.getString(R.string.markdown_remote_image_detail_url_copied),
                        type = ToastType.Success,
                    )
                }
            },
            enabled = resolvedUrl.isNotBlank(),
        ) {
            Text(stringResource(R.string.markdown_remote_image_detail_copy_url))
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text(
            text = stringResource(R.string.markdown_remote_image_detail_chat),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = chatTitleDisplay,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.markdown_remote_image_detail_message),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = messagePositionText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (showLocateButton) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onLocateClick) {
                Text(stringResource(R.string.markdown_remote_image_detail_locate))
            }
        }
        if (settingsHintVisible) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.markdown_remote_image_detail_settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text(
            text = stringResource(R.string.markdown_remote_image_detail_resolution),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = resolutionText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.markdown_remote_image_detail_storage),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text = storageText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

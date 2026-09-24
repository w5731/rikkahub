package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.MarkdownRemoteImageEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.androidx.compose.koinViewModel
import java.io.File

@Composable
fun SettingMarkdownImagesPage(
    vm: MarkdownImagesVM = koinViewModel(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val toaster = LocalToaster.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()

    val items by vm.items.collectAsState()
    var pendingDelete by remember { mutableStateOf<MarkdownRemoteImageEntity?>(null) }
    var previewStartIndex by remember { mutableStateOf<Int?>(null) }
    var refreshingGridIndex by remember { mutableStateOf<Int?>(null) }
    var gridThumbEpoch by remember { mutableStateOf(0) }
    val imageLoader = remember(context) { context.imageLoader }

    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)
    val locateNotFoundToast = stringResource(R.string.markdown_remote_image_locate_not_found)

    if (pendingDelete != null) {
        val target = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.setting_markdown_images_delete_title)) },
            text = {
                Text(
                    target.sourceUrl,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(target.id)
                        toaster.show(deletedToast)
                        pendingDelete = null
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    val previewIndex = previewStartIndex
    if (previewIndex != null && items.isNotEmpty()) {
        val safeStart = previewIndex.coerceIn(0, items.lastIndex)
        val previewSetup = remember(items) {
            val urls = mutableListOf<String>()
            val srcUrls = mutableListOf<String>()
            val missing = mutableSetOf<Int>()
            items.forEachIndexed { index, entity ->
                val f = File(context.filesDir, entity.relativePath)
                if (f.isFile) {
                    urls.add(f.toUri().toString())
                } else {
                    // 设置页里也维持「本地无图」语义：不发请求，由 dialog 渲染破碎图标。
                    urls.add("")
                    missing.add(index)
                }
                srcUrls.add(entity.sourceUrl)
            }
            Triple(urls, srcUrls, missing.toSet())
        }
        ImagePreviewDialog(
            images = previewSetup.first,
            sourceUrls = previewSetup.second,
            initialPage = safeStart,
            missingIndices = previewSetup.third,
            showRefreshButton = true,
            chatImageNavigation = null,
            resolveConversationDetailForExternalSettings = vm::lookupConversationDetailForRemoteUrl,
            onExternalLocate = { url ->
                scope.launch {
                    val target = vm.findConversationAndNodeForRemoteUrl(url)
                    if (target != null) {
                        nav.clearAndNavigate(
                            Screen.Chat(
                                id = target.first.toString(),
                                nodeId = target.second.toString(),
                            ),
                        )
                    } else {
                        toaster.show(locateNotFoundToast, type = ToastType.Warning)
                    }
                }
            },
            onRefreshConfirmed = { page ->
                val safePage = page.coerceIn(0, items.lastIndex)
                val target = items.getOrNull(safePage) ?: return@ImagePreviewDialog null
                refreshingGridIndex = safePage
                try {
                    val refreshedUri = vm.refreshFromNetwork(imageLoader, target.sourceUrl)
                    gridThumbEpoch++
                    refreshedUri.toString()
                } finally {
                    refreshingGridIndex = null
                }
            },
            onDismissRequest = { previewStartIndex = null },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_markdown_remote_images)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Text(
                text = stringResource(R.string.setting_page_markdown_remote_images_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.setting_markdown_images_empty))
                }
            } else {
                LazyVerticalStaggeredGrid(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    state = gridState,
                    columns = StaggeredGridCells.Fixed(2)
                ) {
                    itemsIndexed(items, key = { _, e -> e.id }) { index, entity ->
                        val file = remember(entity.relativePath) {
                            File(context.filesDir, entity.relativePath)
                        }
                        MarkdownRemoteImageCard(
                            entity = entity,
                            file = file,
                            itemIndex = index,
                            thumbEpoch = gridThumbEpoch,
                            showRefreshShimmer = refreshingGridIndex == index,
                            onOpenPreview = { previewStartIndex = it },
                            onDelete = { pendingDelete = entity }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownRemoteImageCard(
    entity: MarkdownRemoteImageEntity,
    file: File,
    itemIndex: Int,
    thumbEpoch: Int,
    showRefreshShimmer: Boolean,
    onOpenPreview: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth()) {
                val ctx = LocalContext.current
                val imageModifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .shimmer(isLoading = showRefreshShimmer)
                    .clickable { onOpenPreview(itemIndex) }
                if (file.isFile) {
                    val imageReq = ImageRequest.Builder(ctx)
                        .data(file)
                        .apply {
                            if (thumbEpoch != 0) {
                                memoryCacheKey("${file.absolutePath}_${thumbEpoch}_${entity.id}")
                                diskCacheKey("${file.absolutePath}_${thumbEpoch}_${entity.id}")
                            }
                        }
                        .build()
                    AsyncImage(
                        model = imageReq,
                        contentDescription = entity.id,
                        modifier = imageModifier,
                        contentScale = ContentScale.Crop
                    )
                } else {
                    val placeholder = if (LocalDarkMode.current) {
                        R.drawable.placeholder_dark
                    } else {
                        R.drawable.placeholder
                    }
                    Image(
                        painter = painterResource(placeholder),
                        contentDescription = stringResource(R.string.markdown_remote_image_broken_cd),
                        modifier = imageModifier,
                        contentScale = ContentScale.Crop,
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.setting_files_page_delete_content_description)
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = entity.sourceUrl,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

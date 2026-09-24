package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.rikkahub.data.aurora.AuroraImageSize
import me.rerere.rikkahub.data.aurora.AuroraImageService
import me.rerere.rikkahub.data.aurora.buildAuroraImageUrl
import me.rerere.rikkahub.data.model.AuroraArtistPreset
import me.rerere.rikkahub.data.model.DEFAULT_ARTIST_PRESETS
import me.rerere.rikkahub.data.model.withNai45FullDefaults
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAuroraImagePage(
    vm: AuroraImageVM = koinViewModel(),
) {
    val config by vm.config.collectAsStateWithLifecycle()
    val checkingTokens by vm.checkingTokens.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var dialog by remember { mutableStateOf<EditDialog>(EditDialog.None) }
    var presetMenuExpanded by remember { mutableStateOf(false) }
    var testNocache by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        vm.messages.collect { toaster.show(it, type = ToastType.Error) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("艾罗拉绘图") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardGroup(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                title = { Text("基础") },
            ) {
                item(
                    headlineContent = { Text("启用艾罗拉绘图") },
                    supportingContent = { Text("开启后，开启了绘图的助手会在回复中自动插入生成图片") },
                    trailingContent = {
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { checked ->
                                vm.update { it.copy(enabled = checked) }
                            },
                        )
                    },
                )
                item(
                    onClick = {
                        dialog = EditDialog.Text(
                            title = "Base URL",
                            initial = config.baseUrl,
                            onConfirm = { v -> vm.update { it.copy(baseUrl = v.trim()) } },
                        )
                    },
                    headlineContent = { Text("Base URL") },
                    supportingContent = { Text(config.baseUrl) },
                )
                item(
                    onClick = {
                        dialog = EditDialog.Text(
                            title = "默认模型",
                            initial = config.defaultModel,
                            onConfirm = { v -> vm.update { it.copy(defaultModel = v.trim()) } },
                        )
                    },
                    headlineContent = { Text("默认模型") },
                    supportingContent = { Text(config.defaultModel) },
                )
                item(
                    onClick = { presetMenuExpanded = true },
                    headlineContent = { Text("默认画师预设") },
                    supportingContent = {
                        Text(config.resolvePreset(config.defaultPresetId)?.name ?: config.defaultPresetId)
                    },
                    trailingContent = {
                        Box {
                            Text("切换", color = MaterialTheme.colorScheme.primary)
                            DropdownMenu(
                                expanded = presetMenuExpanded,
                                onDismissRequest = { presetMenuExpanded = false },
                            ) {
                                config.resolvePresets().forEach { preset ->
                                    DropdownMenuItem(
                                        text = { Text(preset.name) },
                                        onClick = {
                                            presetMenuExpanded = false
                                            vm.update { it.copy(defaultPresetId = preset.id) }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                item(
                    onClick = {
                        dialog = EditDialog.Text(
                            title = "默认负面提示词",
                            initial = config.defaultNegativePrompt,
                            singleLine = false,
                            onConfirm = { v -> vm.update { it.copy(defaultNegativePrompt = v.trim()) } },
                        )
                    },
                    headlineContent = { Text("默认负面提示词") },
                    supportingContent = {
                        Text(
                            config.defaultNegativePrompt,
                            maxLines = 2,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
            }

            CardGroup(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                title = { Text("Token 池") },
            ) {
                config.tokens.forEachIndexed { index, token ->
                    item(
                        onClick = {
                            dialog = EditDialog.Token(
                                initialName = token.name,
                                initialToken = token.token,
                                onConfirm = { name, value -> vm.updateToken(token.id, name, value) },
                                onDelete = { vm.deleteToken(token.id) },
                            )
                        },
                        headlineContent = {
                            Text(token.name.ifBlank { "Token ${index + 1}" })
                        },
                        supportingContent = {
                            val points = when {
                                checkingTokens.contains(token.id) -> "余额查询中…"
                                token.points != null -> "余额 ${token.points} · ${maskToken(token.token)}"
                                else -> maskToken(token.token)
                            }
                            Text(points)
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { vm.checkPoints(token.id) },
                                    enabled = !checkingTokens.contains(token.id),
                                ) { Text("查余额") }
                                Switch(
                                    checked = token.enabled,
                                    onCheckedChange = { checked ->
                                        vm.update { c ->
                                            c.copy(
                                                tokens = c.tokens.map {
                                                    if (it.id == token.id) it.copy(enabled = checked) else it
                                                }
                                            )
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
                item(
                    onClick = {
                        dialog = EditDialog.Token(
                            initialName = "",
                            initialToken = "",
                            onConfirm = { name, value -> vm.addToken(name, value) },
                            onDelete = null,
                        )
                    },
                    leadingContent = { Icon(HugeIcons.Add01, null) },
                    headlineContent = { Text("添加 Token") },
                    supportingContent = { Text("按顺序使用第一个启用的 Token；余额接口 GET /api/points") },
                )
            }

            CardGroup(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                title = { Text("生成参数") },
            ) {
                item(
                    headlineContent = { Text("Steps（迭代步数）") },
                    supportingContent = {
                        ParamSlider(
                            value = config.params.steps.toFloat(),
                            range = 1f..80f,
                            label = { it.toInt().toString() },
                            onCommit = { v ->
                                vm.update { it.copy(params = it.params.copy(steps = v.toInt())) }
                            },
                        )
                    },
                )
                item(
                    headlineContent = { Text("Scale（CFG 强度）") },
                    supportingContent = {
                        ParamSlider(
                            value = config.params.scale,
                            range = 0f..30f,
                            label = { if (it == it.toInt().toFloat()) it.toInt().toString() else "%.1f".format(it) },
                            onCommit = { v ->
                                vm.update { it.copy(params = it.params.copy(scale = v)) }
                            },
                        )
                    },
                )
                item(
                    headlineContent = { Text("CFG Rescale") },
                    supportingContent = {
                        ParamSlider(
                            value = config.params.cfgRescale,
                            range = 0f..1f,
                            label = { "%.2f".format(it) },
                            onCommit = { v ->
                                vm.update { it.copy(params = it.params.copy(cfgRescale = v)) }
                            },
                        )
                    },
                )
                item(
                    onClick = {
                        dialog = EditDialog.Text(
                            title = "Sampler（采样器）",
                            initial = config.params.sampler,
                            onConfirm = { v -> vm.update { it.copy(params = it.params.copy(sampler = v.trim())) } },
                        )
                    },
                    headlineContent = { Text("Sampler（采样器）") },
                    supportingContent = { Text(config.params.sampler) },
                )
                item(
                    onClick = {
                        dialog = EditDialog.Text(
                            title = "Noise Schedule（噪声调度）",
                            initial = config.params.noiseSchedule,
                            onConfirm = { v -> vm.update { it.copy(params = it.params.copy(noiseSchedule = v.trim())) } },
                        )
                    },
                    headlineContent = { Text("Noise Schedule（噪声调度）") },
                    supportingContent = { Text(config.params.noiseSchedule) },
                )
                item(
                    onClick = {
                        vm.update { it.withNai45FullDefaults() }
                        toaster.show("已恢复 NAI 4.5 Full 推荐参数")
                    },
                    headlineContent = { Text("恢复 NAI 4.5 Full 推荐参数") },
                    supportingContent = {
                        Text("28 steps · CFG 10 · Rescale 0.18 · k_euler · 4.5 Full Heavy UCP")
                    },
                )
                item(
                    headlineContent = { Text("单次回复图片数量") },
                    supportingContent = {
                        Column {
                            ParamSlider(
                                value = config.imageCountMin.toFloat(),
                                range = 1f..12f,
                                label = { "最少 ${it.toInt()}" },
                                onCommit = { v ->
                                    vm.update {
                                        it.copy(imageCountMin = v.toInt().coerceAtMost(it.imageCountMax))
                                    }
                                },
                            )
                            ParamSlider(
                                value = config.imageCountMax.toFloat(),
                                range = 1f..12f,
                                label = { "最多 ${it.toInt()}" },
                                onCommit = { v ->
                                    vm.update {
                                        it.copy(imageCountMax = v.toInt().coerceAtLeast(it.imageCountMin))
                                    }
                                },
                            )
                        }
                    },
                )
            }

            CardGroup(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                title = { Text("画师预设") },
            ) {
                config.resolvePresets().forEach { preset ->
                    item(
                        onClick = {
                            dialog = EditDialog.Preset(
                                preset = preset,
                                title = "编辑画师预设",
                                onConfirm = { updated -> vm.savePreset(updated) },
                                onDelete = { vm.deletePreset(preset.id) },
                            )
                        },
                        headlineContent = { Text(preset.name) },
                        supportingContent = {
                            Text(
                                preset.promptPrefix.ifBlank { "（无画师前缀）" },
                                maxLines = 1,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = {
                            if (preset.id == config.defaultPresetId) {
                                Text("默认", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                }
                item(
                    onClick = {
                        dialog = EditDialog.Preset(
                            preset = AuroraArtistPreset(id = "", name = ""),
                            title = "添加画师预设",
                            onConfirm = { vm.savePreset(it) },
                            onDelete = null,
                        )
                    },
                    leadingContent = { Icon(HugeIcons.Add01, null) },
                    headlineContent = { Text("添加画师预设") },
                    supportingContent = { Text("自定义画师/风格前缀、画质后缀与附加负面词") },
                )
                item(
                    onClick = {
                        vm.update { it.copy(artistPresets = DEFAULT_ARTIST_PRESETS) }
                    },
                    leadingContent = { Icon(HugeIcons.Image02, null) },
                    headlineContent = { Text("恢复内置预设") },
                    supportingContent = { Text("重置画师与风格词；模型质量词和负面词由生成参数统一管理") },
                )
            }

            if (config.isUsable()) {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    title = { Text("测试生图") },
                ) {
                    item(
                        onClick = { testNocache = System.currentTimeMillis() },
                        headlineContent = { Text("生成测试图片") },
                        supportingContent = { Text("使用示例标签请求一次，验证 Token 与参数（生成约需 10-60 秒）") },
                        trailingContent = {
                            Icon(HugeIcons.Add01, null)
                        },
                    )
                    val testUrl = remember(config, testNocache) {
                        buildAuroraImageUrl(
                            tag = AuroraImageService.TEST_TAG,
                            size = AuroraImageSize.SQUARE,
                            config = config,
                            token = config.pickToken()?.token,
                            nocache = testNocache,
                        )
                    }
                    if (testNocache != null) {
                        item(
                            headlineContent = {
                                AsyncImage(
                                    model = testUrl,
                                    contentDescription = "测试图片",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 420.dp),
                                )
                            },
                        )
                    }
                }
            }

            dialog.Show { dialog = EditDialog.None }
        }
    }
}

@Composable
private fun ParamSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Column {
        Text(label(local), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range,
        )
    }
}

private fun maskToken(token: String): String =
    when {
        token.isBlank() -> "（未填写）"
        token.length <= 8 -> "****"
        else -> token.take(4) + "****" + token.takeLast(4)
    }

private sealed interface EditDialog {
    data object None : EditDialog

    data class Text(
        val title: String,
        val initial: String,
        val singleLine: Boolean = true,
        val onConfirm: (String) -> Unit,
    ) : EditDialog

    data class Token(
        val initialName: String,
        val initialToken: String,
        val onConfirm: (String, String) -> Unit,
        val onDelete: (() -> Unit)?,
    ) : EditDialog

    data class Preset(
        val preset: AuroraArtistPreset,
        val title: String,
        val onConfirm: (AuroraArtistPreset) -> Unit,
        val onDelete: (() -> Unit)?,
    ) : EditDialog
}

@Composable
private fun EditDialog.Show(dismiss: () -> Unit) {
    when (this) {
        EditDialog.None -> Unit
        is EditDialog.Text -> {
            var value by remember { mutableStateOf(initial) }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(title) },
                text = {
                    TextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = singleLine,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onConfirm(value)
                            dismiss()
                        },
                    ) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = dismiss) { Text("取消") }
                },
            )
        }

        is EditDialog.Token -> {
            var name by remember { mutableStateOf(initialName) }
            var token by remember { mutableStateOf(initialToken) }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text("Token") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("备注（可选）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text("Token") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onConfirm(name, token)
                            dismiss()
                        },
                    ) { Text("确定") }
                },
                dismissButton = {
                    Row {
                        onDelete?.let {
                            TextButton(
                                onClick = {
                                    it()
                                    dismiss()
                                },
                            ) { Text("删除") }
                        }
                        TextButton(onClick = dismiss) { Text("取消") }
                    }
                },
            )
        }

        is EditDialog.Preset -> {
            var name by remember { mutableStateOf(preset.name) }
            var prefix by remember { mutableStateOf(preset.promptPrefix) }
            var suffix by remember { mutableStateOf(preset.promptSuffix) }
            var negative by remember { mutableStateOf(preset.negativePrompt) }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(title) },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
                        OutlinedTextField(value = prefix, onValueChange = { prefix = it }, label = { Text("prompt_prefix（画师/风格前缀）") })
                        OutlinedTextField(value = suffix, onValueChange = { suffix = it }, label = { Text("prompt_suffix（画质后缀，留空用默认画质词）") })
                        OutlinedTextField(value = negative, onValueChange = { negative = it }, label = { Text("negative_prompt（附加负面词）") })
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            onConfirm(preset.copy(name = name, promptPrefix = prefix, promptSuffix = suffix, negativePrompt = negative))
                            dismiss()
                        },
                    ) { Text("确定") }
                },
                dismissButton = {
                    Row {
                        onDelete?.let {
                            TextButton(
                                onClick = {
                                    it()
                                    dismiss()
                                },
                            ) { Text("删除") }
                        }
                        TextButton(onClick = dismiss) { Text("取消") }
                    }
                },
            )
        }
    }
}

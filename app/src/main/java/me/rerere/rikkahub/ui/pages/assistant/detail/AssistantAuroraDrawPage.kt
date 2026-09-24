package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 助手级艾罗拉绘图设置：开关 + 画师预设覆盖。
 * Token/参数等全局配置在「设置 → 艾罗拉绘图」。
 */
@Composable
fun AssistantAuroraDrawPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var presetMenuExpanded by remember { mutableStateOf(false) }

    val auroraConfig = settings.auroraImageConfig
    val globalUsable = auroraConfig.isUsable()
    val presets = auroraConfig.resolvePresets()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("AI 绘图") },
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
            ) {
                item(
                    headlineContent = { Text("启用艾罗拉绘图") },
                    supportingContent = {
                        Text(
                            if (globalUsable) {
                                "回复正文中自动插入 [[aurora_draw]] 生成图片；Token 与参数在全局设置中配置"
                            } else {
                                "全局配置未生效（未启用或缺少可用 Token），请先前往 设置 → 艾罗拉绘图"
                            }
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = assistant.enableAuroraDraw,
                            onCheckedChange = { checked ->
                                vm.update(assistant.copy(enableAuroraDraw = checked))
                            },
                        )
                    },
                )
                item(
                    onClick = { presetMenuExpanded = true },
                    headlineContent = { Text("画师预设") },
                    supportingContent = {
                        val preset = auroraConfig.resolvePreset(assistant.auroraDrawPresetId)
                            ?: auroraConfig.resolvePreset(auroraConfig.defaultPresetId)
                        Text("${preset?.name ?: "默认"}${if (assistant.auroraDrawPresetId == null) "（跟随全局默认）" else ""}")
                    },
                    trailingContent = {
                        Box {
                            Text("切换")
                            DropdownMenu(
                                expanded = presetMenuExpanded,
                                onDismissRequest = { presetMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("跟随全局默认") },
                                    onClick = {
                                        presetMenuExpanded = false
                                        vm.update(assistant.copy(auroraDrawPresetId = null))
                                    },
                                )
                                presets.forEach { preset ->
                                    DropdownMenuItem(
                                        text = { Text(preset.name) },
                                        onClick = {
                                            presetMenuExpanded = false
                                            vm.update(assistant.copy(auroraDrawPresetId = preset.id))
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingAuroraImage) },
                    headlineContent = { Text("全局绘图设置") },
                    supportingContent = { Text("Token 池、生成参数、内置画师预设") },
                )
            }
        }
    }
}

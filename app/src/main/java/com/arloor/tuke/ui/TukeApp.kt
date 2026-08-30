package com.arloor.tuke.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arloor.tuke.di.AppContainer
import com.arloor.tuke.feature.agent.AgentScreen
import com.arloor.tuke.feature.agent.AgentViewModel
import com.arloor.tuke.feature.settings.SettingsScreen
import com.arloor.tuke.feature.settings.SettingsViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TukeApp(container: AppContainer) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: "agent"
    val agentViewModel = rememberViewModel {
        AgentViewModel(
            engineController = container.engineController,
            agentRepository = container.agentRepository,
            keepAlive = container.agentStreamKeepAlive,
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                container.appUpdateChecker.onAppForeground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 聊天页自己做 imePadding。键盘可见时收起底栏、并去掉导航 inset，避免输入框和键盘之间空出一整栏导航高度。
    // 华为在键盘收起后仍可能报告一段非零 IME inset，所以这里用 isImeVisible，而不是用 inset 像素去按比例裁切底栏。
    val imeVisible = WindowInsets.isImeVisible

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets
            .exclude(WindowInsets.ime)
            .let { insets ->
                if (imeVisible) insets.exclude(WindowInsets.navigationBars) else insets
            },
        bottomBar = {
            if (!imeVisible && (current == "agent" || current == "settings")) {
                NavigationBar {
                    NavigationBarItem(
                        selected = current == "agent",
                        onClick = {
                            navController.navigate("agent") {
                                launchSingleTop = true
                                popUpTo("agent") { inclusive = false }
                            }
                        },
                        icon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                        label = { Text("AI 助手") },
                    )
                    NavigationBarItem(
                        selected = current == "settings",
                        onClick = { navController.navigate("settings") { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("设置") },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "agent",
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("agent") {
                AgentScreen(
                    viewModel = agentViewModel,
                    engineController = container.engineController,
                    onOpenSettings = { navController.navigate("settings") },
                )
            }
            composable("settings") {
                val viewModel = rememberViewModel {
                    SettingsViewModel(
                        settingsStore = container.settingsStore,
                        engineController = container.engineController,
                        appUpdateChecker = container.appUpdateChecker,
                    )
                }
                SettingsScreen(viewModel = viewModel)
            }
        }
    }
}

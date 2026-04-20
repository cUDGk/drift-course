package com.driftcourse.app.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.driftcourse.app.settings.SettingsStore
import kotlinx.coroutines.flow.first

private object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
}

@Composable
fun DriftCourseApp() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext as Application) }
    val nav = rememberNavController()

    // token 未設定なら強制的に Settings から始める。設定画面 → 戻る → Chat の導線。
    var start by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val s = store.flow.first()
        start = if (s.token.isBlank()) Routes.SETTINGS else Routes.CHAT
    }

    val startDest = start ?: return

    NavHost(navController = nav, startDestination = startDest) {
        composable(Routes.CHAT) {
            ChatScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = {
                    if (!nav.popBackStack()) {
                        nav.navigate(Routes.CHAT) {
                            popUpTo(Routes.SETTINGS) { inclusive = true }
                        }
                    }
                },
            )
        }
    }
}

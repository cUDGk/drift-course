package com.driftcourse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driftcourse.app.settings.SettingsStore
import com.driftcourse.app.settings.ThemeMode
import com.driftcourse.app.ui.DriftCourseApp
import com.driftcourse.app.ui.theme.DriftCourseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen は super.onCreate より前に呼ぶ必要がある。
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val store = remember { SettingsStore(ctx.applicationContext) }
            val mode by store.themeFlow.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val systemDark = isSystemInDarkTheme()
            val resolvedDark = when (mode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            DriftCourseTheme(darkTheme = resolvedDark) {
                DriftCourseApp()
            }
        }
    }
}

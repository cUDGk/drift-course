package com.driftcourse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driftcourse.app.settings.SettingsStore
import com.driftcourse.app.settings.ThemeMode
import com.driftcourse.app.ui.DriftCourseApp
import com.driftcourse.app.ui.theme.DriftCourseTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // システムスプラッシュ本体 (透明アイコン + グレー背景) を有効化。
        // installSplashScreen は super.onCreate より先に呼ぶ必要がある。
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

            // 全画面スプラッシュ: 起動時に 900ms 表示して 400ms でフェードアウト。
            // Android 12+ のシステムスプラッシュ (丸アイコン) は止められないので一瞬出るが、
            // その直後にこの全画面画像に切り替わる。
            var showSplash by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(900)
                showSplash = false
            }

            DriftCourseTheme(darkTheme = resolvedDark) {
                Box(modifier = Modifier.fillMaxSize()) {
                    DriftCourseApp()
                    AnimatedVisibility(
                        visible = showSplash,
                        enter = androidx.compose.animation.EnterTransition.None,
                        exit = fadeOut(animationSpec = tween(400)),
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.splash),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

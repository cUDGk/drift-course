package com.driftcourse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.driftcourse.app.ui.DriftCourseApp
import com.driftcourse.app.ui.theme.DriftCourseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DriftCourseTheme {
                DriftCourseApp()
            }
        }
    }
}

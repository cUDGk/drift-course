package com.driftcourse.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

object TabRoutes {
    const val CREATE = "tab/create"
    const val MODELS = "tab/models"
    const val CONVERSATIONS = "tab/conversations"
}

private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    TabItem(TabRoutes.CREATE, "モデル作成", Icons.Default.AutoAwesome),
    TabItem(TabRoutes.MODELS, "モデル一覧", Icons.Default.Face),
    TabItem(TabRoutes.CONVERSATIONS, "対話一覧", Icons.AutoMirrored.Filled.Chat),
)

@Composable
fun RootScaffold(
    onOpenSettings: () -> Unit,
    onOpenCharacterNew: () -> Unit,
    onOpenCharacterCreateChat: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenModel: (String) -> Unit,
) {
    val innerNav = rememberNavController()
    val backStack by innerNav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = currentRoute == tab.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentRoute != tab.route) {
                                innerNav.navigate(tab.route) {
                                    popUpTo(innerNav.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = innerNav,
            startDestination = TabRoutes.CREATE,
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            composable(TabRoutes.CREATE) {
                ModelCreateScreen(
                    onOpenSettings = onOpenSettings,
                    onManualCreate = onOpenCharacterNew,
                    onAiCreate = onOpenCharacterCreateChat,
                )
            }
            composable(TabRoutes.MODELS) {
                CharacterListScreen(
                    onOpenSettings = onOpenSettings,
                    onOpenModel = onOpenModel,
                )
            }
            composable(TabRoutes.CONVERSATIONS) {
                ConversationsHomeScreen(
                    onOpenSettings = onOpenSettings,
                    onOpenConversation = onOpenConversation,
                )
            }
        }
    }
}

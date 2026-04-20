package com.driftcourse.app.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.driftcourse.app.settings.SettingsStore
import kotlinx.coroutines.flow.first

private object Routes {
    const val SETTINGS = "settings"
    const val CONVERSATIONS = "conversations"
    const val CHARACTERS = "characters"
    const val CHARACTER_NEW = "character/new"
    const val CHARACTER_EDIT = "character/{id}"
    const val CONVERSATION = "conversation/{id}"
    const val MEMORY = "memory/{convId}"
    const val DEBUG_CHAT = "chat"

    fun characterEdit(id: String) = "character/$id"
    fun conversation(id: String) = "conversation/$id"
    fun memory(convId: String) = "memory/$convId"
}

@Composable
fun DriftCourseApp() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext as Application) }
    val nav = rememberNavController()

    var start by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val s = store.flow.first()
        start = if (s.token.isBlank()) Routes.SETTINGS else Routes.CONVERSATIONS
    }

    val startDest = start ?: return

    NavHost(navController = nav, startDestination = startDest) {
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = {
                    if (!nav.popBackStack()) {
                        nav.navigate(Routes.CONVERSATIONS) {
                            popUpTo(Routes.SETTINGS) { inclusive = true }
                        }
                    }
                },
                onOpenCharacters = { nav.navigate(Routes.CHARACTERS) },
                onOpenDebugChat = { nav.navigate(Routes.DEBUG_CHAT) },
            )
        }

        composable(Routes.CONVERSATIONS) {
            ConversationsHomeScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenConversation = { nav.navigate(Routes.conversation(it)) },
            )
        }

        composable(Routes.CHARACTERS) {
            CharacterListScreen(
                onBack = { nav.popBackStack() },
                onOpenCharacter = { nav.navigate(Routes.characterEdit(it)) },
                onNewCharacter = { nav.navigate(Routes.CHARACTER_NEW) },
            )
        }

        composable(Routes.CHARACTER_NEW) {
            CharacterEditScreen(
                characterId = null,
                onBack = { nav.popBackStack() },
                onOpenConversation = { nav.navigate(Routes.conversation(it)) },
            )
        }

        composable(
            route = Routes.CHARACTER_EDIT,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            CharacterEditScreen(
                characterId = id,
                onBack = { nav.popBackStack() },
                onOpenConversation = { nav.navigate(Routes.conversation(it)) },
            )
        }

        composable(
            route = Routes.CONVERSATION,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ConversationScreen(
                conversationId = id,
                onBack = { nav.popBackStack() },
                onOpenMemory = { nav.navigate(Routes.memory(it)) },
            )
        }

        composable(
            route = Routes.MEMORY,
            arguments = listOf(navArgument("convId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("convId").orEmpty()
            MemoryScreen(conversationId = id, onBack = { nav.popBackStack() })
        }

        composable(Routes.DEBUG_CHAT) {
            ChatScreen(
                onBack = { nav.popBackStack() },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
    }
}

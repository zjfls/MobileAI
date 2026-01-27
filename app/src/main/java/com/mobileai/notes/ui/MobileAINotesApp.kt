package com.mobileai.notes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mobileai.notes.data.DocumentStore
import com.mobileai.notes.ui.screens.EditorScreen
import com.mobileai.notes.ui.screens.HomeScreen

@Composable
fun MobileAINotesApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val store = remember { DocumentStore.create(context.applicationContext) }

    NavHost(
        navController = navController,
        startDestination = Routes.Home,
    ) {
        composable(Routes.Home) {
            HomeScreen(
                store = store,
                onOpenDocument = { docId ->
                    navController.navigate(Routes.editor(docId))
                },
            )
        }
        composable(
            Routes.Editor,
            arguments = listOf(navArgument(Routes.ArgDocId) { type = NavType.StringType }),
        ) { backStackEntry ->
            val docId = backStackEntry.arguments?.getString(Routes.ArgDocId) ?: return@composable
            EditorScreen(
                store = store,
                docId = docId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private object Routes {
    const val ArgDocId = "docId"
    const val Home = "home"
    const val Editor = "editor/{$ArgDocId}"

    fun editor(docId: String) = "editor/$docId"
}

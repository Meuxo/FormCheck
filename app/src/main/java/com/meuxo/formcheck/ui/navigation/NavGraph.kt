package com.meuxo.formcheck.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meuxo.formcheck.ui.screens.AnalysisScreen
import com.meuxo.formcheck.ui.screens.CaptureScreen
import com.meuxo.formcheck.ui.screens.HomeScreen
import com.meuxo.formcheck.ui.screens.ProgressScreen
import com.meuxo.formcheck.ui.screens.ResultsScreen
import com.meuxo.formcheck.ui.screens.TutorialScreen

object Routes {
    const val HOME = "home"
    const val CAPTURE = "capture"
    const val ANALYSIS = "analysis/{mediaUri}"
    const val RESULTS = "results/{sessionId}"
    const val PROGRESS = "progress"
    const val TUTORIAL = "tutorial"

    fun analysis(uri: String) = "analysis/${Uri.encode(uri)}"
    fun results(id: Long) = "results/$id"
}

@Composable
fun NavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onRecord = { navController.navigate(Routes.CAPTURE) },
                onPicked = { uri -> navController.navigate(Routes.analysis(uri)) },
                onOpenSession = { id -> navController.navigate(Routes.results(id)) },
                onOpenProgress = { navController.navigate(Routes.PROGRESS) },
                onOpenTutorial = { navController.navigate(Routes.TUTORIAL) }
            )
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onCaptured = { uri ->
                    navController.navigate(Routes.analysis(uri)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.ANALYSIS,
            arguments = listOf(navArgument("mediaUri") { type = NavType.StringType })
        ) { backStackEntry ->
            // Compose Navigation auto-URL-decodes string path args, so do not decode again.
            val mediaUri = backStackEntry.arguments?.getString("mediaUri").orEmpty()
            AnalysisScreen(
                mediaUri = mediaUri,
                onComplete = { sessionId ->
                    navController.navigate(Routes.results(sessionId)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.RESULTS,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("sessionId") ?: 0L
            ResultsScreen(
                sessionId = id,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.PROGRESS) {
            ProgressScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TUTORIAL) {
            TutorialScreen(onBack = { navController.popBackStack() })
        }
    }
}

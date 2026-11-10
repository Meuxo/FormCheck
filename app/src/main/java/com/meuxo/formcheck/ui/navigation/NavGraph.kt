package com.meuxo.formcheck.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meuxo.formcheck.domain.model.Session
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.domain.model.SessionStatus
import com.meuxo.formcheck.ui.screens.AnalysisProgressScreen
import com.meuxo.formcheck.ui.screens.CaptureScreen
import com.meuxo.formcheck.ui.screens.HomeScreen
import com.meuxo.formcheck.ui.screens.LibraryScreen
import com.meuxo.formcheck.ui.screens.ModeScreen
import com.meuxo.formcheck.ui.screens.NewSessionEvent
import com.meuxo.formcheck.ui.screens.OnboardingScreen
import com.meuxo.formcheck.ui.screens.PersonPickScreen
import com.meuxo.formcheck.ui.screens.PlaybackScreen
import com.meuxo.formcheck.ui.screens.ProgressScreen
import com.meuxo.formcheck.ui.screens.ResultsScreen
import com.meuxo.formcheck.ui.screens.SettingsViewModel
import com.meuxo.formcheck.ui.screens.TutorialScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val MODE = "mode/{type}"
    const val CAPTURE = "capture/{type}"
    const val PERSON = "person/{id}"
    const val ANALYSIS = "analysis/{id}"
    const val RESULTS = "results/{id}"
    const val PLAYBACK = "playback/{id}?frame={frame}&metric={metric}&shot={shot}"
    const val LIBRARY = "library"
    const val PROGRESS = "progress"
    const val TUTORIAL = "tutorial"

    fun mode(t: SessionType) = "mode/${t.name}"
    fun capture(t: SessionType) = "capture/${t.name}"
    fun person(id: Long) = "person/$id"
    fun analysis(id: Long) = "analysis/$id"
    fun results(id: Long) = "results/$id"
    fun playback(id: Long, frame: Int? = null, metric: String? = null, shot: Int? = null) =
        "playback/$id?frame=${frame ?: -1}&metric=${metric ?: ""}&shot=${shot ?: -1}"
}

/** Routing after a session is created. */
fun NavHostController.handle(e: NewSessionEvent) {
    when (e) {
        is NewSessionEvent.PickPerson -> navigate(Routes.person(e.id)) { popUpTo(Routes.HOME) }
        is NewSessionEvent.Calibrate -> navigate(Routes.analysis(e.id)) { popUpTo(Routes.HOME) }
        is NewSessionEvent.Analyzing -> navigate(Routes.analysis(e.id)) { popUpTo(Routes.HOME) }
        is NewSessionEvent.Failed -> Unit
    }
}

fun NavHostController.openSession(s: Session) {
    when (s.status) {
        SessionStatus.DONE, SessionStatus.LEGACY -> navigate(Routes.results(s.id))
        else -> navigate(Routes.analysis(s.id))
    }
}

@Composable
fun NavGraph(settingsVm: SettingsViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val settings by settingsVm.settings.collectAsState(initial = null)
    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val idArg = listOf(navArgument("id") { type = NavType.LongType })

    NavHost(navController = nav, startDestination = if (s.onboarded) Routes.HOME else Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onMode = { nav.navigate(Routes.mode(it)) },
                onOpenSession = { nav.openSession(it) },
                onLibrary = { nav.navigate(Routes.LIBRARY) },
                onProgress = { nav.navigate(Routes.PROGRESS) },
                onTutorial = { nav.navigate(Routes.TUTORIAL) },
                onSettings = { nav.navigate(Routes.ONBOARDING) }
            )
        }
        composable(Routes.MODE, listOf(navArgument("type") { type = NavType.StringType })) { e ->
            val t = SessionType.valueOf(e.arguments?.getString("type") ?: "FORM")
            ModeScreen(t, onBack = { nav.popBackStack() }, onRecord = { nav.navigate(Routes.capture(t)) }, onEvent = nav::handle)
        }
        composable(Routes.CAPTURE, listOf(navArgument("type") { type = NavType.StringType })) { e ->
            val t = SessionType.valueOf(e.arguments?.getString("type") ?: "FORM")
            CaptureScreen(t, onEvent = nav::handle)
        }
        composable(Routes.PERSON, idArg) { e ->
            val id = e.arguments!!.getLong("id")
            PersonPickScreen(id, onChosen = { nav.navigate(Routes.analysis(id)) { popUpTo(Routes.HOME) } })
        }
        composable(Routes.ANALYSIS, idArg) {
            AnalysisProgressScreen(
                onDone = { id -> nav.navigate(Routes.results(id)) { popUpTo(Routes.HOME) } },
                onLeave = { nav.popBackStack(Routes.HOME, false) }
            )
        }
        composable(Routes.RESULTS, idArg) { e ->
            val id = e.arguments!!.getLong("id")
            ResultsScreen(onBack = { nav.popBackStack() }, onPlayback = { f, m, shot -> nav.navigate(Routes.playback(id, f, m?.id?.name, shot)) })
        }
        composable(Routes.PLAYBACK, listOf(
            navArgument("id") { type = NavType.LongType },
            navArgument("frame") { type = NavType.IntType; defaultValue = -1 },
            navArgument("metric") { type = NavType.StringType; defaultValue = "" },
            navArgument("shot") { type = NavType.IntType; defaultValue = -1 }
        )) { e ->
            val a = e.arguments!!
            PlaybackScreen(
                startFrame = a.getInt("frame").takeIf { it >= 0 },
                metricName = a.getString("metric")?.takeIf { it.isNotEmpty() },
                shotIndex = a.getInt("shot").takeIf { it >= 0 },
                onBack = { nav.popBackStack() }
            )
        }
        composable(Routes.LIBRARY) { LibraryScreen(onBack = { nav.popBackStack() }, onOpen = { nav.openSession(it) }) }
        composable(Routes.PROGRESS) { ProgressScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.TUTORIAL) { TutorialScreen(onBack = { nav.popBackStack() }) }
    }
}

package com.focuslock.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.focuslock.app.AppContainer
import com.focuslock.app.block.LockController
import com.focuslock.app.block.LockState
import com.focuslock.app.container
import com.focuslock.app.core.SessionStatus
import com.focuslock.app.data.AppSettings
import com.focuslock.app.session.Notifications
import com.focuslock.app.ui.home.HomeScreen
import com.focuslock.app.ui.home.HomeViewModel
import com.focuslock.app.ui.schedule.ScheduleEditorScreen
import com.focuslock.app.ui.schedule.ScheduleEditorViewModel
import com.focuslock.app.ui.schedule.ScheduleListScreen
import com.focuslock.app.ui.schedule.ScheduleListViewModel
import com.focuslock.app.ui.session.ActiveSessionScreen
import com.focuslock.app.ui.session.CompletionScreen
import com.focuslock.app.ui.session.SessionActions
import com.focuslock.app.ui.settings.SettingsScreen
import com.focuslock.app.ui.settings.SettingsViewModel
import com.focuslock.app.ui.stats.StatsScreen
import com.focuslock.app.ui.stats.StatsViewModel
import com.focuslock.app.ui.theme.FocusLockTheme
import com.focuslock.app.ui.theme.SessionColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single activity. What is shown is decided by the session state, not by navigation:
 * active session → full-screen timer; just finished → completion screen; otherwise the normal app.
 */
class MainActivity : ComponentActivity() {

    /** Session id we already requested pinning for (so we don't re-prompt after the user unpinned). */
    private var pinRequestedFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pinRequestedFor = savedInstanceState?.getString(KEY_PINNED)
        handleIntent(intent)

        val c = container
        setContent {
            // null until DataStore has loaded – so defaults never trigger pinning the user switched off.
            val loadedSettings by produceState<AppSettings?>(initialValue = null) {
                c.settings.flow.collect { value = it }
            }
            val settings = loadedSettings ?: AppSettings()
            val session by c.sessionManager.state.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            val active = session.active

            var lock by remember { mutableStateOf(LockController.state(this)) }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                lock = LockController.state(this@MainActivity)
                scope.launch { c.sessionManager.refreshDnd() }
            }
            // Poll the real lock state while a session runs (user may unpin with the system gesture).
            LaunchedEffect(active != null) {
                while (active != null) {
                    lock = LockController.state(this@MainActivity)
                    delay(1500)
                }
                lock = LockController.state(this@MainActivity)
            }

            // Screen pinning / Lock Task: request once per session, release when it ends.
            LaunchedEffect(active?.sessionId, loadedSettings?.screenPinning) {
                if (active != null && loadedSettings?.screenPinning == true && pinRequestedFor != active.sessionId &&
                    LockController.state(this@MainActivity) == LockState.NONE
                ) {
                    pinRequestedFor = active.sessionId
                    delay(400) // let the session screen draw first
                    LockController.start(this@MainActivity)
                    delay(800)
                    lock = LockController.state(this@MainActivity)
                }
                if (active == null) {
                    LockController.stop(this@MainActivity)
                    lock = LockController.state(this@MainActivity)
                }
            }

            SessionWindowEffects(
                active = active != null,
                keepScreenOn = active != null && active.status == SessionStatus.RUNNING && settings.keepScreenOn,
                dim = settings.dimScreen,
            )

            FocusLockTheme(settings.themeMode) {
                // Hebrew UI: always right-to-left, even if the device language is not Hebrew.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.fillMaxSize().background(if (active != null) SessionColors.background else MaterialTheme.colorScheme.background)) {
                        val completion = session.completion
                        when {
                            active != null -> ActiveSessionScreen(
                                snapshot = active,
                                clocks = c.sessionManager.clocks,
                                dnd = session.dnd,
                                lock = lock,
                                pinningWanted = settings.screenPinning,
                                keepSilenceDuringPause = settings.keepSilenceDuringPause,
                                actions = SessionActions(
                                    pause = { scope.launch { c.sessionManager.pause() } },
                                    resume = { scope.launch { c.sessionManager.resume() } },
                                    finishEarly = { scope.launch { c.sessionManager.finishEarly() } },
                                    timeUp = { scope.launch { c.sessionManager.checkCompletion() } },
                                    repin = {
                                        LockController.start(this@MainActivity)
                                        scope.launch { delay(800); lock = LockController.state(this@MainActivity) }
                                    },
                                    setKeepSilenceDuringPause = { v -> scope.launch { c.sessionManager.setKeepSilenceDuringPause(v) } },
                                ),
                            )
                            completion != null -> CompletionScreen(
                                summary = completion,
                                onAgain = {
                                    c.sessionManager.dismissCompletion()
                                    scope.launch {
                                        c.sessionManager.start((completion.plannedMs / 60_000).toInt(), completion.taskName)
                                    }
                                },
                                onHome = { c.sessionManager.dismissCompletion() },
                            )
                            else -> AppNavigation(c)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PINNED, pinRequestedFor)
    }

    override fun onResume() {
        super.onResume()
        Notifications.cancelResult(this)
    }

    /** "Start session" button of a scheduled-session notification. */
    private fun handleIntent(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_START_SCHEDULED_ID, -1L) ?: -1L
        if (id <= 0) return
        intent?.removeExtra(EXTRA_START_SCHEDULED_ID)
        val c = container
        lifecycleScope.launch {
            Notifications.cancelScheduled(this@MainActivity, id)
            val e = c.scheduleRepository.get(id) ?: return@launch
            c.sessionManager.start(e.durationMin, e.title, scheduledId = e.id, rememberAsDefault = false)
        }
    }

    @Composable
    private fun SessionWindowEffects(active: Boolean, keepScreenOn: Boolean, dim: Boolean) {
        DisposableEffect(active, keepScreenOn, dim) {
            val w = window
            if (keepScreenOn) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            w.attributes = w.attributes.apply {
                screenBrightness = if (keepScreenOn && dim) DIM_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }

            val insets = WindowCompat.getInsetsController(w, w.decorView)
            if (active) {
                insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insets.hide(WindowInsetsCompat.Type.systemBars())
                insets.isAppearanceLightStatusBars = false
            } else {
                insets.show(WindowInsetsCompat.Type.systemBars())
            }
            // Show the timer over the lock screen while a session is active (no unlock bypass – timer only).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(active)
            onDispose { }
        }
    }

    companion object {
        const val EXTRA_START_SCHEDULED_ID = "start_scheduled_id"
        private const val KEY_PINNED = "pin_requested_for"
        private const val DIM_BRIGHTNESS = 0.08f
    }
}

@Composable
private fun AppNavigation(c: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            val vm = viewModel { HomeViewModel(c.settings, c.sessionManager) }
            HomeScreen(
                vm = vm,
                onSchedule = { nav.navigate("schedule") },
                onStats = { nav.navigate("stats") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("schedule") {
            val vm = viewModel { ScheduleListViewModel(c.scheduleRepository) }
            ScheduleListScreen(vm, onBack = { nav.popBackStack() }, onEdit = { id -> nav.navigate("schedule/edit/$id") })
        }
        composable(
            "schedule/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: -1L
            val vm = viewModel(key = "edit-$id") { ScheduleEditorViewModel(c.scheduleRepository, id) }
            ScheduleEditorScreen(vm, onClose = { nav.popBackStack() })
        }
        composable("stats") {
            val vm = viewModel { StatsViewModel(c.database.sessionRecordDao()) }
            StatsScreen(vm, onBack = { nav.popBackStack() })
        }
        composable("settings") {
            val vm = viewModel { SettingsViewModel(c.settings, c.sessionManager, c.scheduleRepository) }
            SettingsScreen(vm, onBack = { nav.popBackStack() })
        }
    }
}

package com.focuslock.app

import android.app.Application
import android.content.Context
import com.focuslock.app.block.DistractionController
import com.focuslock.app.block.LockController
import com.focuslock.app.data.ActiveSessionStore
import com.focuslock.app.data.SettingsRepository
import com.focuslock.app.data.db.AppDatabase
import com.focuslock.app.schedule.ScheduleAlarmScheduler
import com.focuslock.app.schedule.ScheduleRepository
import com.focuslock.app.session.AndroidClocks
import com.focuslock.app.session.Notifications
import com.focuslock.app.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container – small app, no DI framework needed. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database = AppDatabase.build(context)
    val settings = SettingsRepository(context)
    val distraction = DistractionController(context)
    val sessionManager = SessionManager(
        context = context,
        store = ActiveSessionStore(context),
        settings = settings,
        distraction = distraction,
        records = database.sessionRecordDao(),
        clocks = AndroidClocks(context),
    )
    val scheduleRepository = ScheduleRepository(database.scheduleDao(), ScheduleAlarmScheduler(context))
}

class FocusLockApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        LockController.configureIfDeviceOwner(this)
        // Recover an active session (or clean up after a crash) whenever the process starts.
        container.appScope.launch { container.sessionManager.restore() }
    }
}

val Context.container: AppContainer
    get() = (applicationContext as FocusLockApp).container

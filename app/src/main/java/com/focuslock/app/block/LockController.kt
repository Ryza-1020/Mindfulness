package com.focuslock.app.block

import com.focuslock.app.util.systemService

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

/** What screen restriction is really in effect (read back from the system, never assumed). */
enum class LockState { NONE, PINNED, LOCKED }

/**
 * Two levels of app restriction, both through public Android APIs:
 *
 * 1. Regular personal device → **screen pinning**. [Activity.startLockTask] on an app that is not
 *    allow-listed asks the user to confirm pinning. While pinned, Home/Recents/notifications are
 *    blocked, but the user can always unpin with the system gesture. Nothing is bypassed.
 *
 * 2. Managed / dedicated device → **Lock Task mode**. Only when a device owner has allow-listed
 *    FocusLock ([DevicePolicyManager.isLockTaskPermitted]); then the same call locks without a prompt
 *    and the user exits through FocusLock itself (pause → finish early). We never request device owner
 *    on a personal phone; the README explains the manual adb setup for a dedicated device.
 *
 * No root, no accessibility-service tricks, no overlay hacks.
 */
object LockController {
    private const val TAG = "FocusLockLock"

    fun state(context: Context): LockState {
        val am = context.systemService<ActivityManager>()
        return when (am.lockTaskModeState) {
            ActivityManager.LOCK_TASK_MODE_PINNED -> LockState.PINNED
            ActivityManager.LOCK_TASK_MODE_LOCKED -> LockState.LOCKED
            else -> LockState.NONE
        }
    }

    fun isManagedLockPermitted(context: Context): Boolean =
        context.systemService<DevicePolicyManager>().isLockTaskPermitted(context.packageName)

    fun isDeviceOwner(context: Context): Boolean =
        context.systemService<DevicePolicyManager>().isDeviceOwnerApp(context.packageName)

    /** Requests pinning / lock task. Returns false if the system refused. */
    fun start(activity: Activity): Boolean = try {
        activity.startLockTask()
        true
    } catch (e: Exception) {
        Log.w(TAG, "startLockTask failed", e)
        false
    }

    fun stop(activity: Activity) {
        if (state(activity) == LockState.NONE) return
        try {
            activity.stopLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask failed", e)
        }
    }

    /**
     * When FocusLock *is* the device owner (dedicated device set up via adb), allow-list itself for
     * Lock Task and keep the power menu available (emergency calls / SOS stay reachable).
     */
    fun configureIfDeviceOwner(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = context.systemService<DevicePolicyManager>()
        val admin = FocusDeviceAdminReceiver.component(context)
        try {
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                        DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lock task configuration failed", e)
        }
    }

    /** Safe way out of managed mode, from inside the app. */
    @Suppress("DEPRECATION")
    fun releaseDeviceOwner(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = context.systemService<DevicePolicyManager>()
        try {
            dpm.setLockTaskPackages(FocusDeviceAdminReceiver.component(context), emptyArray())
            dpm.clearDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "clearDeviceOwnerApp failed", e)
        }
    }
}

/** Device-admin receiver – only becomes active if the user explicitly sets FocusLock as device owner via adb. */
class FocusDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context) = ComponentName(context, FocusDeviceAdminReceiver::class.java)
    }
}

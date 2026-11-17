package com.focuslock.app.block

import com.focuslock.app.util.systemService

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import android.util.Log
import com.focuslock.app.R
import com.focuslock.app.data.CallsPolicy
import com.focuslock.app.ui.MainActivity

/** What the silencing layer is actually doing right now – shown to the user verbatim. */
enum class DndStatus { ACTIVE, OFF, PAUSED, NO_PERMISSION, ERROR }

data class DndConfig(
    val calls: CallsPolicy,
    val repeatCallers: Boolean,
    /** Also switch the phone's ringer to silent (no ring, no vibration) for the session. */
    val silenceRinger: Boolean,
)

/**
 * Silences notifications and calls through Android's official APIs only, in three layers:
 *
 * 1. **App-owned Do Not Disturb rule** (Android 10+): an [AutomaticZenRule] switched on/off with
 *    [NotificationManager.setAutomaticZenRuleState]. Doesn't touch the user's own DND settings.
 * 2. **Verified fallback**: some OEM builds accept the rule but don't actually enter DND. We read the
 *    real interruption filter back; if DND is still off we switch the global filter instead (also the
 *    only option on Android 8–9). The user's previous filter + policy are saved first and restored
 *    only if the filter is still ours at the end.
 * 3. **Ringer → silent** (optional, default on): the same as the user pressing "silent mode".
 *    The previous ringer mode is saved and restored only if nobody changed it meanwhile.
 *    This makes calls silent even on devices where DND exceptions behave unexpectedly
 *    (note: it also silences calls that the DND exceptions would let through).
 *
 * Silencing a call never rejects it: the phone still receives it and shows a missed call.
 * Alarms stay allowed. All saved state lives on disk, so a crash or reboot can be cleaned up.
 *
 * Requires "Do Not Disturb access" (ACCESS_NOTIFICATION_POLICY), granted by the user in system settings.
 */
class DistractionController(private val context: Context) {

    private val nm = context.systemService<NotificationManager>()
    private val audio = context.systemService<AudioManager>()
    private val prefs = context.getSharedPreferences("dnd_state", Context.MODE_PRIVATE)
    private val conditionUri: Uri = Uri.parse("condition://${context.packageName}/focus_session")

    fun hasAccess(): Boolean = nm.isNotificationPolicyAccessGranted

    /** True if we changed anything that still needs restoring (survives crashes / reboots). */
    fun isAppliedByUs(): Boolean =
        prefs.getBoolean(K_RULE_ON, false) || prefs.getBoolean(K_GLOBAL_ON, false) || prefs.getBoolean(K_RINGER_ON, false)

    fun activate(config: DndConfig): DndStatus {
        if (!hasAccess()) return DndStatus.NO_PERMISSION
        var ok = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activateRule(config)
                if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                    Log.w(TAG, "Zen rule accepted but DND still off – using global filter")
                    activateGlobal(config)
                }
            } else {
                activateGlobal(config)
            }
        } catch (e: Exception) {
            Log.w(TAG, "DND activation failed", e)
            ok = false
        }
        try {
            if (config.silenceRinger) silenceRinger() else restoreRinger()
        } catch (e: Exception) {
            Log.w(TAG, "Ringer change failed", e)
        }
        return when {
            ok -> DndStatus.ACTIVE
            prefs.getBoolean(K_RINGER_ON, false) -> DndStatus.ACTIVE // at least the ringer is silent
            else -> DndStatus.ERROR
        }
    }

    /** Undo only what we did. Safe to call repeatedly. */
    fun deactivate() {
        if (!isAppliedByUs()) return
        val access = hasAccess()
        try {
            if (access && prefs.getBoolean(K_RULE_ON, false)) deactivateRule()
        } catch (e: Exception) {
            Log.w(TAG, "Rule deactivation failed", e)
        }
        try {
            if (access && prefs.getBoolean(K_GLOBAL_ON, false)) restoreGlobal()
        } catch (e: Exception) {
            Log.w(TAG, "Global DND restore failed", e)
        }
        try {
            restoreRinger()
        } catch (e: Exception) {
            Log.w(TAG, "Ringer restore failed", e)
        }
        prefs.edit()
            .putBoolean(K_RULE_ON, false)
            .putBoolean(K_GLOBAL_ON, false)
            .putBoolean(K_RINGER_ON, false)
            .commit()
    }

    // ---------- Layer 1 (Android 10+): app-owned automatic zen rule ----------

    private fun buildPolicy(config: DndConfig): ZenPolicy {
        val people = when (config.calls) {
            CallsPolicy.NONE -> ZenPolicy.PEOPLE_TYPE_NONE
            CallsPolicy.STARRED -> ZenPolicy.PEOPLE_TYPE_STARRED
            CallsPolicy.CONTACTS -> ZenPolicy.PEOPLE_TYPE_CONTACTS
        }
        val b = ZenPolicy.Builder()
            .allowCalls(people)
            .allowRepeatCallers(config.repeatCallers)
            .allowMessages(ZenPolicy.PEOPLE_TYPE_NONE)
            .allowAlarms(true)
            .allowMedia(true)
            .allowSystem(false)
            .allowReminders(false)
            .allowEvents(false)
            .hideAllVisualEffects()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
        }
        return b.build()
    }

    @Suppress("NewApi")
    private fun activateRule(config: DndConfig) {
        val rule = AutomaticZenRule(
            context.getString(R.string.app_name),
            null,
            ComponentName(context, MainActivity::class.java),
            conditionUri,
            buildPolicy(config),
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true,
        )
        var id = prefs.getString(K_RULE_ID, null)
        if (id == null || nm.getAutomaticZenRule(id) == null) {
            id = nm.addAutomaticZenRule(rule)
            prefs.edit().putString(K_RULE_ID, id).commit()
        } else {
            nm.updateAutomaticZenRule(id, rule)
        }
        prefs.edit().putBoolean(K_RULE_ON, true).commit()
        nm.setAutomaticZenRuleState(id, Condition(conditionUri, context.getString(R.string.notif_running_title), Condition.STATE_TRUE))
    }

    @Suppress("NewApi")
    private fun deactivateRule() {
        val id = prefs.getString(K_RULE_ID, null) ?: return
        if (nm.getAutomaticZenRule(id) == null) return
        nm.setAutomaticZenRuleState(id, Condition(conditionUri, context.getString(R.string.notif_running_title), Condition.STATE_FALSE))
    }

    // ---------- Layer 2: global filter with save/restore ----------

    @Suppress("DEPRECATION")
    private fun activateGlobal(config: DndConfig) {
        if (!prefs.getBoolean(K_GLOBAL_ON, false)) {
            // Save the user's original state BEFORE changing it.
            val p = nm.notificationPolicy
            prefs.edit()
                .putInt(K_ORIG_FILTER, nm.currentInterruptionFilter)
                .putInt(K_ORIG_CATS, p.priorityCategories)
                .putInt(K_ORIG_CALLS, p.priorityCallSenders)
                .putInt(K_ORIG_MSGS, p.priorityMessageSenders)
                .putInt(K_ORIG_VISUAL, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.suppressedVisualEffects else 0)
                .putBoolean(K_GLOBAL_ON, true)
                .commit()
        }
        var cats = NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) cats = cats or NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA
        val callSenders = when (config.calls) {
            CallsPolicy.NONE -> NotificationManager.Policy.PRIORITY_SENDERS_STARRED // ignored: calls not in cats
            CallsPolicy.STARRED -> NotificationManager.Policy.PRIORITY_SENDERS_STARRED
            CallsPolicy.CONTACTS -> NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS
        }
        if (config.calls != CallsPolicy.NONE) cats = cats or NotificationManager.Policy.PRIORITY_CATEGORY_CALLS
        if (config.repeatCallers) cats = cats or NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS
        nm.notificationPolicy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            NotificationManager.Policy(cats, callSenders, NotificationManager.Policy.PRIORITY_SENDERS_STARRED, ALL_VISUAL_EFFECTS)
        } else {
            NotificationManager.Policy(cats, callSenders, NotificationManager.Policy.PRIORITY_SENDERS_STARRED)
        }
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }

    @Suppress("DEPRECATION")
    private fun restoreGlobal() {
        // If the user changed DND themselves during the session, don't override their choice.
        if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_PRIORITY) return
        val cats = prefs.getInt(K_ORIG_CATS, 0)
        val calls = prefs.getInt(K_ORIG_CALLS, NotificationManager.Policy.PRIORITY_SENDERS_STARRED)
        val msgs = prefs.getInt(K_ORIG_MSGS, NotificationManager.Policy.PRIORITY_SENDERS_STARRED)
        nm.notificationPolicy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            NotificationManager.Policy(cats, calls, msgs, prefs.getInt(K_ORIG_VISUAL, 0))
        } else {
            NotificationManager.Policy(cats, calls, msgs)
        }
        nm.setInterruptionFilter(prefs.getInt(K_ORIG_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL))
    }

    // ---------- Layer 3: ringer mode ----------

    private fun silenceRinger() {
        if (!prefs.getBoolean(K_RINGER_ON, false)) {
            prefs.edit()
                .putInt(K_ORIG_RINGER, audio.ringerMode)
                .putBoolean(K_RINGER_ON, true)
                .commit()
        }
        if (audio.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        }
    }

    private fun restoreRinger() {
        if (!prefs.getBoolean(K_RINGER_ON, false)) return
        // Restore only if still silent – if the user turned the sound back on themselves, respect that.
        if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            audio.ringerMode = prefs.getInt(K_ORIG_RINGER, AudioManager.RINGER_MODE_NORMAL)
        }
        prefs.edit().putBoolean(K_RINGER_ON, false).commit()
    }

    private companion object {
        const val TAG = "FocusLockDnd"
        const val K_RULE_ID = "rule_id"
        const val K_RULE_ON = "rule_on"
        const val K_GLOBAL_ON = "global_on"
        const val K_RINGER_ON = "ringer_on"
        const val K_ORIG_FILTER = "orig_filter"
        const val K_ORIG_CATS = "orig_cats"
        const val K_ORIG_CALLS = "orig_calls"
        const val K_ORIG_MSGS = "orig_msgs"
        const val K_ORIG_VISUAL = "orig_visual"
        const val K_ORIG_RINGER = "orig_ringer"

        @Suppress("DEPRECATION")
        val ALL_VISUAL_EFFECTS =
            NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT or
                NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS or
                NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK or
                NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR or
                NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE or
                NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT
    }
}

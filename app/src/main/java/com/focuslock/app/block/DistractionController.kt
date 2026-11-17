package com.focuslock.app.block

import com.focuslock.app.util.systemService

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
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
)

/**
 * Silences notifications and calls through Android's official Do Not Disturb APIs only.
 *
 * Android 10+ (API 29): an app-owned [AutomaticZenRule] that we switch on/off with
 * [NotificationManager.setAutomaticZenRuleState]. This never overwrites the user's own DND
 * configuration: deactivating our rule leaves any DND the user turned on by hand untouched.
 *
 * Android 8–9: there are no app rules, so we change the global filter. The previous filter and
 * policy are saved to disk *before* changing anything, and are restored only if the filter is
 * still the one we set (if the user changed DND during the session, we respect that).
 *
 * Important: silencing a call does not reject it – the phone still receives it and it appears
 * as a missed call. Alarms are always allowed.
 *
 * Requires the user to grant "Do Not Disturb access" (ACCESS_NOTIFICATION_POLICY) in system settings.
 */
class DistractionController(private val context: Context) {

    private val nm = context.systemService<NotificationManager>()
    private val prefs = context.getSharedPreferences("dnd_state", Context.MODE_PRIVATE)

    fun hasAccess(): Boolean = nm.isNotificationPolicyAccessGranted

    /** True if we turned DND on and haven't turned it off yet (survives crashes / reboots). */
    fun isAppliedByUs(): Boolean = prefs.getBoolean(K_APPLIED, false)

    fun activate(config: DndConfig): DndStatus {
        if (!hasAccess()) return DndStatus.NO_PERMISSION
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) activateRule(config) else activateLegacy(config)
            DndStatus.ACTIVE
        } catch (e: Exception) {
            Log.w(TAG, "DND activation failed", e)
            DndStatus.ERROR
        }
    }

    /** Undo only what we did. Safe to call repeatedly. */
    fun deactivate() {
        if (!isAppliedByUs()) return
        try {
            if (hasAccess()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) deactivateRule() else restoreLegacy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "DND deactivation failed", e)
        } finally {
            prefs.edit().putBoolean(K_APPLIED, false).commit()
        }
    }

    // ---------- Android 10+ : automatic zen rule ----------

    private val conditionUri: Uri = Uri.parse("condition://${context.packageName}/focus_session")

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
        prefs.edit().putBoolean(K_APPLIED, true).commit()
        nm.setAutomaticZenRuleState(id, Condition(conditionUri, context.getString(R.string.notif_running_title), Condition.STATE_TRUE))
    }

    @Suppress("NewApi")
    private fun deactivateRule() {
        val id = prefs.getString(K_RULE_ID, null) ?: return
        if (nm.getAutomaticZenRule(id) == null) return
        nm.setAutomaticZenRuleState(id, Condition(conditionUri, context.getString(R.string.notif_running_title), Condition.STATE_FALSE))
    }

    // ---------- Android 8–9 : global filter with save/restore ----------

    @Suppress("DEPRECATION")
    private fun activateLegacy(config: DndConfig) {
        if (!isAppliedByUs()) {
            // Save the user's original state BEFORE changing it.
            val p = nm.notificationPolicy
            prefs.edit()
                .putInt(K_ORIG_FILTER, nm.currentInterruptionFilter)
                .putInt(K_ORIG_CATS, p.priorityCategories)
                .putInt(K_ORIG_CALLS, p.priorityCallSenders)
                .putInt(K_ORIG_MSGS, p.priorityMessageSenders)
                .putInt(K_ORIG_VISUAL, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.suppressedVisualEffects else 0)
                .putBoolean(K_APPLIED, true)
                .commit()
        }
        var cats = NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) cats = cats or NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA
        val callSenders = when (config.calls) {
            CallsPolicy.NONE -> NotificationManager.Policy.PRIORITY_SENDERS_STARRED // ignored, calls not in cats
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
    private fun restoreLegacy() {
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

    private companion object {
        const val TAG = "FocusLockDnd"
        const val K_APPLIED = "applied"
        const val K_RULE_ID = "rule_id"
        const val K_ORIG_FILTER = "orig_filter"
        const val K_ORIG_CATS = "orig_cats"
        const val K_ORIG_CALLS = "orig_calls"
        const val K_ORIG_MSGS = "orig_msgs"
        const val K_ORIG_VISUAL = "orig_visual"

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

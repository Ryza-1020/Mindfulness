package com.focuslock.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.focuslock.app.R
import com.focuslock.app.block.LockController
import com.focuslock.app.data.AppSettings
import com.focuslock.app.data.CallsPolicy
import com.focuslock.app.data.SettingsRepository
import com.focuslock.app.data.ThemeMode
import com.focuslock.app.schedule.ScheduleRepository
import com.focuslock.app.session.SessionManager
import com.focuslock.app.ui.components.PermissionState
import com.focuslock.app.ui.components.SectionTitle
import com.focuslock.app.ui.components.SwitchRow
import com.focuslock.app.ui.components.SystemSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val sessions: SessionManager,
    private val schedules: ScheduleRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = repo.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            repo.update(transform)
            sessions.refreshDnd() // apply DND changes to a running session immediately
        }
    }

    /** After returning from system settings: DND access / exact alarms may have changed. */
    fun onPermissionsChanged(exactAlarmsGrantedNow: Boolean) {
        viewModelScope.launch {
            sessions.refreshDnd()
            if (exactAlarmsGrantedNow) schedules.rescheduleAll()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val settingsOrNull by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var perms by remember { mutableStateOf(PermissionState.read(context)) }
    var managedPermitted by remember { mutableStateOf(LockController.isManagedLockPermitted(context)) }
    var deviceOwner by remember { mutableStateOf(LockController.isDeviceOwner(context)) }
    var confirmRelease by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val before = perms
        perms = PermissionState.read(context)
        managedPermitted = LockController.isManagedLockPermitted(context)
        deviceOwner = LockController.isDeviceOwner(context)
        if (before != perms) vm.onPermissionsChanged(!before.exactAlarms && perms.exactAlarms)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        perms = PermissionState.read(context)
        // Android stops showing the dialog after repeated denials – then the system screen is the only way.
        if (!granted) SystemSettings.notifications(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        val s = settingsOrNull ?: return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            // ---------------- Permissions first: they decide what can work ----------------
            SectionTitle(stringResource(R.string.settings_section_permissions))
            PermissionRow(
                title = stringResource(R.string.perm_dnd),
                description = stringResource(R.string.perm_dnd_desc),
                granted = perms.dnd,
                onGrant = { SystemSettings.dndAccess(context) },
            )
            PermissionRow(
                title = stringResource(R.string.perm_notifications),
                description = stringResource(R.string.perm_notifications_desc),
                granted = perms.notifications,
                onGrant = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        SystemSettings.notifications(context)
                    }
                },
                onOpenSettings = { SystemSettings.notifications(context) },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PermissionRow(
                    title = stringResource(R.string.perm_exact),
                    description = stringResource(R.string.perm_exact_desc),
                    granted = perms.exactAlarms,
                    onGrant = { SystemSettings.exactAlarms(context) },
                )
            }
            PermissionRow(
                title = stringResource(R.string.perm_battery),
                description = stringResource(R.string.perm_battery_desc),
                granted = perms.batteryUnrestricted,
                onGrant = { SystemSettings.batteryOptimization(context) },
            )

            HorizontalDivider(Modifier.padding(top = 12.dp))

            // ---------------- Silencing ----------------
            SectionTitle(stringResource(R.string.settings_section_block))
            SwitchRow(
                title = stringResource(R.string.settings_block_notifications),
                description = stringResource(R.string.settings_block_notifications_desc),
                checked = s.blockNotifications,
                onCheckedChange = { v -> vm.update { it.copy(blockNotifications = v) } },
            )
            SwitchRow(
                title = stringResource(R.string.settings_silence_ringer),
                description = stringResource(R.string.settings_silence_ringer_desc),
                checked = s.silenceRinger,
                enabled = s.blockNotifications,
                onCheckedChange = { v -> vm.update { it.copy(silenceRinger = v) } },
            )
            Text(
                stringResource(R.string.settings_calls),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            listOf(
                CallsPolicy.NONE to R.string.calls_none,
                CallsPolicy.STARRED to R.string.calls_starred,
                CallsPolicy.CONTACTS to R.string.calls_contacts,
            ).forEach { (policy, label) ->
                RadioRow(
                    label = stringResource(label),
                    selected = s.callsPolicy == policy,
                    enabled = s.blockNotifications,
                    onSelect = { vm.update { it.copy(callsPolicy = policy) } },
                )
            }
            SwitchRow(
                title = stringResource(R.string.settings_repeat_callers),
                checked = s.allowRepeatCallers,
                enabled = s.blockNotifications,
                onCheckedChange = { v -> vm.update { it.copy(allowRepeatCallers = v) } },
            )
            SwitchRow(
                title = stringResource(R.string.session_keep_silence_paused),
                checked = s.keepSilenceDuringPause,
                enabled = s.blockNotifications,
                onCheckedChange = { v -> vm.update { it.copy(keepSilenceDuringPause = v) } },
            )
            NoteText(stringResource(R.string.settings_block_note))

            // ---------------- Lock ----------------
            SectionTitle(stringResource(R.string.settings_section_lock))
            SwitchRow(
                title = stringResource(R.string.settings_pinning),
                description = stringResource(R.string.settings_pinning_desc),
                checked = s.screenPinning,
                onCheckedChange = { v -> vm.update { it.copy(screenPinning = v) } },
            )
            NoteText(stringResource(R.string.settings_pinning_setup))
            OutlinedButton(onClick = { SystemSettings.security(context) }, modifier = Modifier.padding(top = 6.dp)) {
                Text(stringResource(R.string.settings_open_security))
            }
            Spacer(Modifier.height(10.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        stringResource(if (managedPermitted) R.string.settings_managed_available else R.string.settings_managed_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (deviceOwner) {
                        TextButton(onClick = { confirmRelease = true }) { Text(stringResource(R.string.settings_remove_owner)) }
                    }
                }
            }

            // ---------------- Screen ----------------
            SectionTitle(stringResource(R.string.settings_section_screen))
            SwitchRow(
                title = stringResource(R.string.settings_keep_screen_on),
                description = stringResource(R.string.settings_keep_screen_on_desc),
                checked = s.keepScreenOn,
                onCheckedChange = { v -> vm.update { it.copy(keepScreenOn = v) } },
            )
            SwitchRow(
                title = stringResource(R.string.settings_dim),
                checked = s.dimScreen,
                enabled = s.keepScreenOn,
                onCheckedChange = { v -> vm.update { it.copy(dimScreen = v) } },
            )

            // ---------------- Schedule ----------------
            SectionTitle(stringResource(R.string.settings_section_schedule))
            SwitchRow(
                title = stringResource(R.string.settings_auto_start),
                description = stringResource(R.string.settings_auto_start_desc),
                checked = s.autoStartScheduled,
                onCheckedChange = { v -> vm.update { it.copy(autoStartScheduled = v) } },
            )

            // ---------------- Look ----------------
            SectionTitle(stringResource(R.string.settings_section_look))
            listOf(
                ThemeMode.SYSTEM to R.string.theme_system,
                ThemeMode.LIGHT to R.string.theme_light,
                ThemeMode.DARK to R.string.theme_dark,
            ).forEach { (mode, label) ->
                RadioRow(stringResource(label), s.themeMode == mode, onSelect = { vm.update { it.copy(themeMode = mode) } })
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmRelease) {
        AlertDialog(
            onDismissRequest = { confirmRelease = false },
            text = { Text(stringResource(R.string.settings_remove_owner_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    LockController.releaseDeviceOwner(context)
                    deviceOwner = LockController.isDeviceOwner(context)
                    managedPermitted = LockController.isManagedLockPermitted(context)
                    confirmRelease = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { confirmRelease = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun NoteText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(if (granted) R.string.perm_granted else R.string.perm_missing),
                style = MaterialTheme.typography.labelMedium,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (!granted) {
            FilledTonalButton(onClick = onGrant) { Text(stringResource(R.string.perm_grant)) }
        } else if (onOpenSettings != null) {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings_title)) }
        }
    }
}

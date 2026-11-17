package com.focuslock.app.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.focuslock.app.R
import com.focuslock.app.data.SettingsRepository
import com.focuslock.app.session.SessionManager
import com.focuslock.app.ui.components.DurationPicker
import com.focuslock.app.ui.components.PermissionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUi(
    val selectedMinutes: Int = 25,
    val task: String = "",
    val blockNotifications: Boolean = true,
)

class HomeViewModel(
    private val settings: SettingsRepository,
    private val sessions: SessionManager,
) : ViewModel() {
    /** null until the user picks something → falls back to the last saved duration. */
    private val selected = MutableStateFlow<Int?>(null)
    private val task = MutableStateFlow("")

    val ui: StateFlow<HomeUi> = combine(selected, task, settings.flow) { sel, t, s ->
        HomeUi(selectedMinutes = sel ?: s.lastDurationMin, task = t, blockNotifications = s.blockNotifications)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    fun select(minutes: Int) { selected.value = minutes }
    fun setTask(text: String) { task.value = text.take(80) }

    fun start() {
        val u = ui.value
        viewModelScope.launch { sessions.start(u.selectedMinutes, u.task) }
    }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onSchedule: () -> Unit,
    onStats: () -> Unit,
    onSettings: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var perms by remember { mutableStateOf(PermissionState.read(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { perms = PermissionState.read(context) }

    // Android 13+: ask for notifications the first time a session starts; start regardless of the answer.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    val onStart = {
        val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onStats) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.home_stats)) }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.home_settings)) }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(28.dp))

        Text(stringResource(R.string.home_duration_label), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        DurationPicker(selected = ui.selectedMinutes, onSelect = vm::select)

        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = ui.task,
            onValueChange = vm::setTask,
            label = { Text(stringResource(R.string.home_task_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (ui.blockNotifications && !perms.dnd) {
            Spacer(Modifier.height(16.dp))
            Card(
                onClick = onSettings,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            ) {
                Text(stringResource(R.string.home_dnd_missing), Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onStart,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(76.dp),
        ) {
            Text(stringResource(R.string.home_start), style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onSchedule,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Default.DateRange, contentDescription = null)
            Spacer(Modifier.padding(4.dp))
            Text(stringResource(R.string.home_schedule), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(24.dp))
    }
}

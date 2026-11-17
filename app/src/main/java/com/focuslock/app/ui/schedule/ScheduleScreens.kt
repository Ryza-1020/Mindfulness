package com.focuslock.app.ui.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.focuslock.app.R
import com.focuslock.app.core.DaysMask
import com.focuslock.app.core.RepeatType
import com.focuslock.app.core.ScheduleCalculator
import com.focuslock.app.data.db.ScheduledSessionEntity
import com.focuslock.app.schedule.ScheduleRepository
import com.focuslock.app.ui.components.DurationPicker
import com.focuslock.app.ui.components.PermissionState
import com.focuslock.app.ui.components.SectionTitle
import com.focuslock.app.ui.components.SystemSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HEBREW = Locale.forLanguageTag("he")
private val dayLetters = mapOf(
    DayOfWeek.SUNDAY to "א׳", DayOfWeek.MONDAY to "ב׳", DayOfWeek.TUESDAY to "ג׳", DayOfWeek.WEDNESDAY to "ד׳",
    DayOfWeek.THURSDAY to "ה׳", DayOfWeek.FRIDAY to "ו׳", DayOfWeek.SATURDAY to "ש׳",
)

private fun timeText(h: Int, m: Int) = String.format(Locale.US, "%02d:%02d", h, m)

// ------------------------------------------------------------------------------------------------
// List
// ------------------------------------------------------------------------------------------------

class ScheduleListViewModel(private val repo: ScheduleRepository) : ViewModel() {
    val items: StateFlow<List<ScheduledSessionEntity>> =
        repo.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(id: Long, enabled: Boolean) = viewModelScope.launch { repo.setEnabled(id, enabled) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
    /** Re-arm after the user granted exact alarms. */
    fun rearm() = viewModelScope.launch { repo.rescheduleAll() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleListScreen(vm: ScheduleListViewModel, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var perms by remember { mutableStateOf(PermissionState.read(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val before = perms.exactAlarms
        perms = PermissionState.read(context)
        if (!before && perms.exactAlarms) vm.rearm()
    }
    var toDelete by remember { mutableStateOf<ScheduledSessionEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schedule_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(-1L) }) { Icon(Icons.Default.Add, stringResource(R.string.schedule_add)) }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!perms.exactAlarms) {
                item {
                    Card(
                        onClick = { SystemSettings.exactAlarms(context) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                    ) { Text(stringResource(R.string.schedule_exact_missing), Modifier.padding(14.dp)) }
                }
            }
            if (items.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.schedule_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }
            }
            items(items, key = { it.id }) { e ->
                ScheduleItem(e, onClick = { onEdit(e.id) }, onToggle = { vm.setEnabled(e.id, it) }, onDelete = { toDelete = e })
            }
        }
    }

    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.schedule_delete_confirm)) },
            confirmButton = { TextButton(onClick = { vm.delete(e.id); toDelete = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun repeatLabel(e: ScheduledSessionEntity): String = when (e.repeat) {
    RepeatType.ONCE -> {
        val date = e.dateEpochDay?.let { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("d.M.yyyy")) } ?: ""
        stringResource(R.string.repeat_once) + " · " + date
    }
    RepeatType.DAILY -> stringResource(R.string.repeat_daily)
    RepeatType.WORKDAYS -> stringResource(R.string.repeat_workdays)
    RepeatType.CUSTOM -> DaysMask.WEEK_ORDER.filter { DaysMask.contains(e.daysMask, it) }.joinToString(" ") { dayLetters.getValue(it) }
}

@Composable
private fun ScheduleItem(e: ScheduledSessionEntity, onClick: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(timeText(e.hour, e.minute), style = MaterialTheme.typography.headlineSmall)
                Text(
                    e.title.ifBlank { stringResource(R.string.schedule_untitled) } + " · " + stringResource(R.string.minutes_long, e.durationMin),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(repeatLabel(e), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val next = e.nextTriggerAt
                Text(
                    if (e.enabled && next != null) {
                        stringResource(
                            R.string.schedule_next,
                            Instant.ofEpochMilli(next).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("EEEE d.M HH:mm", HEBREW)),
                        )
                    } else {
                        stringResource(R.string.schedule_disabled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Switch(checked = e.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Editor
// ------------------------------------------------------------------------------------------------

data class EditorState(
    val id: Long = 0,
    val title: String = "",
    val durationMin: Int = 60,
    val hour: Int = 9,
    val minute: Int = 0,
    val repeat: RepeatType = RepeatType.WORKDAYS,
    val daysMask: Int = DaysMask.WORKDAYS_IL,
    val dateEpochDay: Long = LocalDate.now().toEpochDay(),
    val loaded: Boolean = false,
)

enum class EditorError { NO_DAYS, IN_PAST }

class ScheduleEditorViewModel(private val repo: ScheduleRepository, private val editId: Long) : ViewModel() {
    private val _state = MutableStateFlow(EditorState(loaded = editId <= 0))
    val state: StateFlow<EditorState> = _state.asStateFlow()

    init {
        if (editId > 0) viewModelScope.launch {
            val e = repo.get(editId)
            _state.value = if (e == null) EditorState(loaded = true) else EditorState(
                id = e.id, title = e.title, durationMin = e.durationMin, hour = e.hour, minute = e.minute,
                repeat = e.repeat,
                daysMask = if (e.repeat == RepeatType.CUSTOM) e.daysMask else DaysMask.WORKDAYS_IL,
                dateEpochDay = e.dateEpochDay ?: LocalDate.now().toEpochDay(),
                loaded = true,
            )
        }
    }

    fun update(transform: (EditorState) -> EditorState) { _state.value = transform(_state.value) }

    fun validate(): EditorError? {
        val s = _state.value
        if (s.repeat == RepeatType.CUSTOM && s.daysMask == 0) return EditorError.NO_DAYS
        val rule = com.focuslock.app.core.ScheduleRule(s.repeat, s.hour, s.minute, s.daysMask, s.dateEpochDay)
        if (ScheduleCalculator.nextTrigger(rule, ZonedDateTime.now()) == null) return EditorError.IN_PAST
        return null
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        viewModelScope.launch {
            repo.save(
                ScheduledSessionEntity(
                    id = s.id,
                    title = s.title.trim(),
                    durationMin = s.durationMin,
                    hour = s.hour,
                    minute = s.minute,
                    repeat = s.repeat,
                    daysMask = if (s.repeat == RepeatType.CUSTOM) s.daysMask else 0,
                    dateEpochDay = if (s.repeat == RepeatType.ONCE) s.dateEpochDay else null,
                    enabled = true,
                    nextTriggerAt = null,
                ),
            )
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = _state.value.id
        viewModelScope.launch {
            if (id > 0) repo.delete(id)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditorScreen(vm: ScheduleEditorViewModel, onClose: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var showTime by rememberSaveable { mutableStateOf(false) }
    var showDate by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<EditorError?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (s.id > 0) R.string.schedule_edit else R.string.schedule_add)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    if (s.id > 0) IconButton(onClick = { vm.delete(onClose) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                },
            )
        },
    ) { padding ->
        if (!s.loaded) return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = s.title,
                onValueChange = { t -> vm.update { it.copy(title = t.take(80)) } },
                label = { Text(stringResource(R.string.schedule_task)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle(stringResource(R.string.schedule_duration))
            DurationPicker(selected = s.durationMin, onSelect = { m -> vm.update { it.copy(durationMin = m) } })

            SectionTitle(stringResource(R.string.schedule_start_time))
            OutlinedButton(onClick = { showTime = true }, modifier = Modifier.height(56.dp)) {
                Text(timeText(s.hour, s.minute), style = MaterialTheme.typography.headlineSmall)
            }

            SectionTitle(stringResource(R.string.schedule_repeat))
            listOf(
                RepeatType.ONCE to R.string.repeat_once,
                RepeatType.DAILY to R.string.repeat_daily,
                RepeatType.WORKDAYS to R.string.repeat_workdays,
                RepeatType.CUSTOM to R.string.repeat_custom,
            ).forEach { (type, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = s.repeat == type, role = Role.RadioButton, onClick = { vm.update { it.copy(repeat = type) } })
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.repeat == type, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                }
            }

            if (s.repeat == RepeatType.ONCE) {
                SectionTitle(stringResource(R.string.schedule_start_date))
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.height(56.dp)) {
                    Text(
                        LocalDate.ofEpochDay(s.dateEpochDay).format(DateTimeFormatter.ofPattern("EEEE, d.M.yyyy", HEBREW)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            if (s.repeat == RepeatType.CUSTOM) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DaysMask.WEEK_ORDER.forEach { day ->
                        FilterChip(
                            selected = DaysMask.contains(s.daysMask, day),
                            onClick = { vm.update { it.copy(daysMask = DaysMask.toggle(it.daysMask, day)) } },
                            label = { Text(dayLetters.getValue(day)) },
                            modifier = Modifier.height(44.dp),
                        )
                    }
                }
            }

            error?.let { err ->
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(if (err == EditorError.NO_DAYS) R.string.schedule_no_days else R.string.schedule_past),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    error = vm.validate()
                    if (error == null) vm.save(onClose)
                },
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) { Text(stringResource(R.string.save), style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showTime) {
        val tp = rememberTimePickerState(initialHour = s.hour, initialMinute = s.minute, is24Hour = true)
        Dialog(onDismissRequest = { showTime = false }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // The clock dial is drawn LTR so the numbers are not mirrored in the RTL UI.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { TimePicker(state = tp) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showTime = false }) { Text(stringResource(R.string.cancel)) }
                        TextButton(onClick = {
                            vm.update { it.copy(hour = tp.hour, minute = tp.minute) }
                            showTime = false
                        }) { Text(stringResource(R.string.ok)) }
                    }
                }
            }
        }
    }

    if (showDate) {
        val dp = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.ofEpochDay(s.dateEpochDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dp.selectedDateMillis?.let { ms ->
                        val day = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                        vm.update { it.copy(dateEpochDay = day) }
                    }
                    showDate = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = dp) }
    }
}

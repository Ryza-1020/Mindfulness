package com.focuslock.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.focuslock.app.R

val PRESET_DURATIONS = listOf(15, 25, 45, 60, 90)

/** Quick duration chips + "other" (custom minutes). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DurationPicker(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var showCustom by rememberSaveable { mutableStateOf(false) }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PRESET_DURATIONS.forEach { m ->
            FilterChip(
                selected = selected == m,
                onClick = { onSelect(m) },
                label = { Text(stringResource(R.string.minutes_short, m), style = MaterialTheme.typography.titleMedium) },
                modifier = Modifier.height(48.dp),
            )
        }
        val isCustom = selected !in PRESET_DURATIONS
        FilterChip(
            selected = isCustom,
            onClick = { showCustom = true },
            label = {
                Text(
                    if (isCustom) stringResource(R.string.minutes_short, selected) else stringResource(R.string.home_custom),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            modifier = Modifier.height(48.dp),
        )
    }
    if (showCustom) {
        CustomDurationDialog(
            initial = selected,
            onDismiss = { showCustom = false },
            onConfirm = { onSelect(it); showCustom = false },
        )
    }
}

@Composable
private fun CustomDurationDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in 1..600
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_custom_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { t -> text = t.filter(Char::isDigit).take(3) },
                label = { Text(stringResource(R.string.home_custom_hint)) },
                singleLine = true,
                isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { value?.let(onConfirm) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

/** A full-width row with a title, optional description and a switch. The whole row is tappable. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (description != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

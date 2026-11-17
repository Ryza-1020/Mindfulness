package com.focuslock.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.focuslock.app.R
import com.focuslock.app.core.StatsCalculator
import com.focuslock.app.core.StatsSummary
import com.focuslock.app.core.roundedMinutes
import com.focuslock.app.data.db.SessionRecordDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate

data class StatsUi(val summary: StatsSummary? = null, val allTimeMinutes: Int = 0)

class StatsViewModel(dao: SessionRecordDao) : ViewModel() {
    private val today = LocalDate.now().toEpochDay()

    val ui: StateFlow<StatsUi> = combine(dao.observeSince(today - 6), dao.observeTotalMs()) { records, total ->
        StatsUi(StatsCalculator.summarize(records.map { it.toData() }, today), roundedMinutes(total))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUi())
}

private val shortDay = mapOf(
    DayOfWeek.SUNDAY to "א׳", DayOfWeek.MONDAY to "ב׳", DayOfWeek.TUESDAY to "ג׳", DayOfWeek.WEDNESDAY to "ד׳",
    DayOfWeek.THURSDAY to "ה׳", DayOfWeek.FRIDAY to "ו׳", DayOfWeek.SATURDAY to "ש׳",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: StatsViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        val s = ui.summary
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s == null) return@Column
            if (s.weekMinutes == 0 && s.weekCompleted == 0 && s.weekEndedEarly == 0) {
                Text(
                    stringResource(R.string.stats_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(stringResource(R.string.stats_today), stringResource(R.string.minutes_long, s.todayMinutes), Modifier.weight(1f))
                StatCard(stringResource(R.string.stats_week), stringResource(R.string.minutes_long, s.weekMinutes), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(stringResource(R.string.stats_completed), s.weekCompleted.toString(), Modifier.weight(1f))
                StatCard(stringResource(R.string.stats_early), s.weekEndedEarly.toString(), Modifier.weight(1f))
            }

            s.weekRatio?.let { ratio ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        Text(stringResource(R.string.stats_ratio), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Text("${(ratio * 100).toInt()}%", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { ratio },
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(stringResource(R.string.stats_chart_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    BarChart(s)
                }
            }

            if (s.weekEndedEarly > 0) {
                Text(
                    stringResource(R.string.stats_early_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.stats_all_time, stringResource(R.string.minutes_long, ui.allTimeMinutes)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/** Simple 7-day bar chart. In RTL the oldest day is on the right, today on the left – natural for Hebrew. */
@Composable
private fun BarChart(s: StatsSummary) {
    val max = (s.bars.maxOfOrNull { it.minutes } ?: 0).coerceAtLeast(1)
    val chartHeight = 140.dp
    Row(
        Modifier.fillMaxWidth().height(chartHeight + 40.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom,
    ) {
        s.bars.forEach { bar ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(
                    if (bar.minutes > 0) bar.minutes.toString() else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.height(chartHeight).width(22.dp), contentAlignment = Alignment.BottomCenter) {
                    val frac = bar.minutes.toFloat() / max
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(if (bar.minutes > 0) frac.coerceAtLeast(0.03f) else 0.015f)
                            .background(
                                if (bar.minutes > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
                            ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    shortDay.getValue(LocalDate.ofEpochDay(bar.dayEpoch).dayOfWeek),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

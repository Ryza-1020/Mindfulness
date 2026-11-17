package com.focuslock.app.ui.session

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focuslock.app.R
import com.focuslock.app.block.DndStatus
import com.focuslock.app.block.LockState
import com.focuslock.app.core.Clocks
import com.focuslock.app.core.SessionSnapshot
import com.focuslock.app.core.SessionStatus
import com.focuslock.app.core.TimerMath
import com.focuslock.app.core.formatHms
import com.focuslock.app.core.roundedMinutes
import com.focuslock.app.session.CompletionSummary
import com.focuslock.app.ui.theme.SessionColors
import com.focuslock.app.ui.theme.TimerTextStyle
import kotlinx.coroutines.delay

/** Callbacks of the active-session screens. */
data class SessionActions(
    val pause: () -> Unit,
    val resume: () -> Unit,
    val finishEarly: () -> Unit,
    val timeUp: () -> Unit,
    val repin: () -> Unit,
    val setKeepSilenceDuringPause: (Boolean) -> Unit,
)

/**
 * Full-screen, dark session UI. The displayed time is always recomputed from the persisted snapshot
 * ([TimerMath]); the 1-second loop only triggers redraws, it never "counts".
 */
@Composable
fun ActiveSessionScreen(
    snapshot: SessionSnapshot,
    clocks: Clocks,
    dnd: DndStatus,
    lock: LockState,
    pinningWanted: Boolean,
    keepSilenceDuringPause: Boolean,
    actions: SessionActions,
) {
    val context = LocalContext.current
    val backMessage = stringResource(R.string.session_back_blocked)
    BackHandler { Toast.makeText(context, backMessage, Toast.LENGTH_SHORT).show() }

    var now by remember { mutableLongStateOf(clocks.elapsedRealtime()) }
    LaunchedEffect(snapshot) {
        while (true) {
            now = clocks.elapsedRealtime()
            if (snapshot.status == SessionStatus.RUNNING && TimerMath.isFinished(snapshot, clocks)) {
                actions.timeUp()
                break
            }
            delay(1000 - (now % 1000).coerceIn(0, 999) + 5)
        }
    }
    // `now` is read so the composition refreshes every tick.
    @Suppress("UNUSED_VARIABLE") val tick = now
    val remaining = TimerMath.remainingMs(snapshot, clocks)
    val progress = TimerMath.progress(snapshot, clocks)

    Box(
        Modifier
            .fillMaxSize()
            .background(SessionColors.background)
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        StatusLine(dnd, lock, pinningWanted, actions.repin, Modifier.align(Alignment.TopCenter))
        if (snapshot.status == SessionStatus.RUNNING) {
            RunningContent(snapshot, remaining, progress, actions.pause, Modifier.align(Alignment.Center))
        } else {
            PausedContent(remaining, progress, keepSilenceDuringPause, actions, Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun TimerText(remainingMs: Long, color: androidx.compose.ui.graphics.Color = SessionColors.text) {
    // Digits are always laid out left-to-right, also inside the RTL UI.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(formatHms(remainingMs), style = TimerTextStyle, color = color, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SessionProgress(progress: Float) {
    LinearProgressIndicator(
        progress = { progress },
        color = SessionColors.accent,
        trackColor = SessionColors.track,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
        modifier = Modifier.fillMaxWidth(0.8f).height(6.dp),
    )
}

@Composable
private fun RunningContent(s: SessionSnapshot, remaining: Long, progress: Float, onPause: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (s.taskName.isNotBlank()) {
            Text(
                s.taskName,
                color = SessionColors.dimText,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
        }
        TimerText(remaining)
        Spacer(Modifier.height(24.dp))
        SessionProgress(progress)
        Spacer(Modifier.height(64.dp))
        OutlinedButton(
            onClick = onPause,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SessionColors.text),
            modifier = Modifier.width(220.dp).height(60.dp),
        ) {
            Text(stringResource(R.string.session_pause), fontSize = 20.sp)
        }
    }
}

@Composable
private fun PausedContent(
    remaining: Long,
    progress: Float,
    keepSilence: Boolean,
    actions: SessionActions,
    modifier: Modifier,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.session_paused_title),
            color = SessionColors.text,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.session_remaining), color = SessionColors.dimText, style = MaterialTheme.typography.titleMedium)
        TimerText(remaining, color = SessionColors.dimText)
        Spacer(Modifier.height(16.dp))
        SessionProgress(progress)
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = actions.resume,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SessionColors.accent, contentColor = SessionColors.background),
            modifier = Modifier.fillMaxWidth(0.8f).height(64.dp),
        ) {
            Text(stringResource(R.string.session_resume), fontSize = 20.sp)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = { confirm = true },
            modifier = Modifier.fillMaxWidth(0.8f).height(56.dp),
        ) {
            Text(stringResource(R.string.session_finish_early), color = SessionColors.text, fontSize = 18.sp)
        }
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth(0.85f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.session_keep_silence_paused),
                color = SessionColors.dimText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = keepSilence,
                onCheckedChange = actions.setKeepSilenceDuringPause,
                colors = SwitchDefaults.colors(checkedTrackColor = SessionColors.accent),
            )
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.session_finish_confirm_title)) },
            text = { Text(stringResource(R.string.session_finish_confirm_text)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; actions.finishEarly() }) {
                    Text(stringResource(R.string.session_finish_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.session_finish_confirm_no)) }
            },
        )
    }
}

/** Small, dim line telling the user exactly what is active right now. */
@Composable
private fun StatusLine(dnd: DndStatus, lock: LockState, pinningWanted: Boolean, onRepin: () -> Unit, modifier: Modifier) {
    val dndText = when (dnd) {
        DndStatus.ACTIVE -> R.string.status_dnd_active
        DndStatus.OFF -> R.string.status_dnd_off
        DndStatus.PAUSED -> R.string.status_dnd_paused
        DndStatus.NO_PERMISSION -> R.string.status_dnd_no_permission
        DndStatus.ERROR -> R.string.status_dnd_error
    }
    val lockText = when (lock) {
        LockState.NONE -> R.string.status_lock_none
        LockState.PINNED -> R.string.status_lock_pinned
        LockState.LOCKED -> R.string.status_lock_locked
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(dndText) + "  ·  " + stringResource(lockText),
            color = SessionColors.dimText,
            style = MaterialTheme.typography.labelMedium,
        )
        if (pinningWanted && lock == LockState.NONE) {
            TextButton(onClick = onRepin) {
                Text(stringResource(R.string.session_repin), color = SessionColors.accent, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** "Well done" screen. */
@Composable
fun CompletionScreen(summary: CompletionSummary, onAgain: () -> Unit, onHome: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(if (summary.completed) R.string.done_title_completed else R.string.done_title_early),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        if (!summary.completed) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.done_subtitle_early),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(20.dp))
        if (summary.taskName.isNotBlank()) {
            Text(summary.taskName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        Text(
            stringResource(R.string.done_worked, stringResource(R.string.minutes_long, roundedMinutes(summary.workedMs))),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onAgain,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text(stringResource(R.string.done_again), fontSize = 20.sp) }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onHome,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(stringResource(R.string.done_home), fontSize = 18.sp) }
    }
}

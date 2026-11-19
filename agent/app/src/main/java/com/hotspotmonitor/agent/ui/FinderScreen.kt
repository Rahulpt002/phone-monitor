package com.hotspotmonitor.agent.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.finder.FinderEngineImpl
import com.hotspotmonitor.agent.finder.FinderMath
import com.hotspotmonitor.agent.finder.FinderUiState
import com.hotspotmonitor.agent.finder.OrientationMath
import com.hotspotmonitor.agent.finder.SpotCsv
import com.hotspotmonitor.agent.protocol.FinderLive
import com.hotspotmonitor.agent.protocol.FinderMode
import com.hotspotmonitor.agent.protocol.FinderSpot
import com.hotspotmonitor.agent.protocol.Grade
import com.hotspotmonitor.agent.protocol.MetricStats
import com.hotspotmonitor.agent.protocol.SpeedSession
import com.hotspotmonitor.agent.protocol.gradeRsrp
import com.hotspotmonitor.agent.protocol.gradeRsrq
import com.hotspotmonitor.agent.protocol.gradeScore
import com.hotspotmonitor.agent.protocol.gradeSinr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Signal Finder screen (plan 7.3), built to be read outdoors in sunlight: very large score, strong
 * grade colours, big buttons. Owner: finder builder.
 */

private val FinderExcellent = Color(0xFF0B7A34)
private val FinderGood = Color(0xFF8BC34A)
private val FinderFair = Color(0xFFFFB300)
private val FinderPoor = Color(0xFFD32F2F)
private val FinderWarningRed = Color(0xFFC62828)
private val FinderBaselineBlue = Color(0xFF1565C0)

private fun finderGradeColor(grade: Grade): Color = when (grade) {
    Grade.EXCELLENT -> FinderExcellent
    Grade.GOOD -> FinderGood
    Grade.FAIR -> FinderFair
    Grade.POOR -> FinderPoor
}

/** Text on a grade colour: black on the light ones, white on the dark ones. */
private fun finderOnGradeColor(grade: Grade): Color = when (grade) {
    Grade.GOOD, Grade.FAIR -> Color.Black
    Grade.EXCELLENT, Grade.POOR -> Color.White
}

private fun finderGradeLabel(grade: Grade): String = grade.name.lowercase().replaceFirstChar { it.uppercase() }

private val FINDER_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault())

private fun finderLocalTime(iso: String): String =
    Clock.parseIso(iso)?.let { FINDER_TIME_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: iso

private fun finderNum(v: Double?, decimals: Int = 1): String =
    if (v == null || !v.isFinite()) "–" else String.format(Locale.US, "%.${decimals}f", v).removeSuffix(".0")

private fun finderOrientationLabel(o: String?): String = when (o) {
    null -> "–"
    OrientationMath.UPRIGHT -> "Upright"
    OrientationMath.FLAT -> "Flat"
    OrientationMath.ROT90 -> "Turned 90°"
    else -> o
}

@Composable
fun FinderScreen(graph: AgentGraph) {
    val engine = graph.finder
    val impl = engine as? FinderEngineImpl
    val mode by engine.mode.collectAsStateWithLifecycle()
    val live by engine.live.collectAsStateWithLifecycle()
    val spots by engine.spots.collectAsStateWithLifecycle(initialValue = emptyList())
    val fallbackUi = remember { MutableStateFlow(FinderUiState()) }
    val ui by (impl?.ui ?: fallbackUi).collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Live values at 1 Hz only while this screen is visible (or another consumer needs them).
    if (impl != null) {
        LifecycleStartEffect(impl) {
            impl.setScreenOpen(true)
            onStopOrDispose { impl.setScreenOpen(false) }
        }
    }

    // Keep the screen on while walking or testing outdoors.
    val view = LocalView.current
    val keepOn = ui.walking || ui.runningTest != null
    DisposableEffect(view, keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    var showSpotDialog by rememberSaveable { mutableStateOf(false) }
    var showSpeedDialog by rememberSaveable { mutableStateOf(false) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPhotoSpot by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteCandidate by remember { mutableStateOf<FinderSpot?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val spotId = pendingPhotoSpot
        pendingPhotoSpot = null
        if (impl == null || spotId == null) return@rememberLauncherForActivityResult
        val captured = impl.photoCaptureFile()
        if (!ok) {
            captured.delete()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val attached = runCatching { impl.attachPhoto(spotId, captured) }.getOrDefault(false)
            if (!attached) finderToast(context, "Could not save the photo")
        }
    }

    fun capturePhoto(spotId: String) {
        val owner = impl ?: return
        try {
            val file = owner.photoCaptureFile()
            file.parentFile?.mkdirs()
            file.delete()
            val uri = FileProvider.getUriForFile(context, FinderEngineImpl.FILE_PROVIDER_AUTHORITY, file)
            pendingPhotoSpot = spotId
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pendingPhotoSpot = null
            finderToast(context, "No camera app on this phone")
        } catch (e: Exception) {
            pendingPhotoSpot = null
            finderToast(context, "Camera failed: ${e.message}")
        }
    }

    fun exportAndShare() {
        scope.launch {
            try {
                val file = engine.exportCsv()
                val uri = FileProvider.getUriForFile(context, FinderEngineImpl.FILE_PROVIDER_AUTHORITY, file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Signal Finder spots")
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Share spots CSV"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finderToast(context, "Export failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    val busy = ui.runningTest != null

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { FinderLiveCard(live, mode, ui) }
        item { FinderMetricsRow(live) }
        item { FinderCellCard(live) }
        if (busy || live?.progress != null) {
            item { FinderProgressCard(live, ui, onCancel = { impl?.cancelTest() ?: engine.stopFinder() }) }
        }
        item {
            FinderControlsCard(
                ui = ui,
                busy = busy,
                onWalk = { if (ui.walking) impl?.stopWalk() ?: engine.stopFinder() else engine.startWalk() },
                onMute = { impl?.setMuted(!ui.muted) },
                onSpotTest = { showSpotDialog = true },
                onSpeedTest = { showSpeedDialog = true },
                onExport = { exportAndShare() },
                onHeadless = { engine.setHeadless(it) },
                onStopAll = { engine.stopFinder() },
            )
        }
        if (ui.message != null || ui.error != null) {
            item { FinderMessageCard(ui, onAddPhoto = ui.lastSpotId?.let { id -> { capturePhoto(id) } }) }
        }
        item {
            Column {
                Text(
                    "Saved spots (${spots.size})",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Ranked by measured download speed, then score.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (spots.isEmpty()) {
            item { Text("No spots yet. Run a spot test at each candidate place.", fontSize = 18.sp) }
        }
        itemsIndexed(spots, key = { _, s -> s.id }) { index, spot ->
            FinderSpotCard(
                rank = index + 1,
                spot = spot,
                expanded = expandedId == spot.id,
                busy = busy,
                photo = impl?.photoFile(spot.id)?.takeIf { spot.hasPhoto == true },
                onToggle = { expandedId = if (expandedId == spot.id) null else spot.id },
                onSpeedTest = {
                    if (impl != null) impl.launchSpeedTest(spot.id) else scope.launch { runCatching { engine.speedTest(spot.id) } }
                },
                onBaseline = {
                    scope.launch {
                        if (!engine.setBaseline(spot.id)) finderToast(context, "Spot not found")
                    }
                },
                onPhoto = { capturePhoto(spot.id) },
                onDelete = { deleteCandidate = spot },
            )
        }
    }

    if (showSpotDialog) {
        FinderSpotTestDialog(
            defaultName = FinderMath.nextSpotName(spots.map { it.name }),
            defaultDurationS = graph.settings.current.finder.spotTestS,
            onDismiss = { showSpotDialog = false },
            onStart = { name, orientation, duration ->
                showSpotDialog = false
                if (impl != null) {
                    impl.launchSpotTest(name, orientation, duration)
                } else {
                    scope.launch { runCatching { engine.spotTest(name, orientation, duration) } }
                }
            },
        )
    }

    if (showSpeedDialog) {
        val finder = graph.settings.current.finder
        FinderSpeedTestDialog(
            spots = spots,
            runs = finder.speedRuns,
            bytesPerRun = finder.speedBytes + finder.speedBytes / 4,
            onDismiss = { showSpeedDialog = false },
            onStart = { spotId ->
                showSpeedDialog = false
                if (impl != null) impl.launchSpeedTest(spotId) else scope.launch { runCatching { engine.speedTest(spotId) } }
            },
        )
    }

    deleteCandidate?.let { spot ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete \"${spot.name}\"?") },
            text = { Text("The spot, its speed tests and its photo are removed here and on the Server.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    scope.launch { engine.deleteSpot(spot.id) }
                }) { Text("Delete", color = FinderWarningRed) }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Keep") } },
        )
    }
}

private fun finderToast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

// --- live --------------------------------------------------------------------------------------

@Composable
private fun FinderLiveCard(live: FinderLive?, mode: FinderMode, ui: FinderUiState) {
    val score = live?.score
    val grade = score?.let(::gradeScore)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FinderBadge(mode.wire.uppercase(), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                if (ui.mirror) {
                    Spacer(Modifier.width(8.dp))
                    FinderBadge("DASHBOARD WATCHING", FinderBaselineBlue, Color.White)
                }
                if (ui.muted) {
                    Spacer(Modifier.width(8.dp))
                    FinderBadge("MUTED", MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surface)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = score?.roundToInt()?.toString() ?: "--",
                    fontSize = 112.sp,
                    lineHeight = 112.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.padding(bottom = 16.dp)) {
                    Text(
                        grade?.let(::finderGradeLabel) ?: if (live == null) "Waiting for readings" else "No signal",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = grade?.let(::finderGradeColor) ?: MaterialTheme.colorScheme.onSurface,
                    )
                    Text("Score (raw ${finderNum(live?.scoreRaw)})", fontSize = 18.sp)
                }
            }
            FinderScoreBar(score)
        }
    }
}

@Composable
private fun FinderScoreBar(score: Double?) {
    val fraction = ((score ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    val color = score?.let { finderGradeColor(gradeScore(it)) } ?: MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}

@Composable
private fun FinderMetricsRow(live: FinderLive?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FinderMetricTile("RSRP", live?.rsrp, "dBm", live?.rsrp?.let(::gradeRsrp), Modifier.weight(1f))
        FinderMetricTile("SINR", live?.sinr, "dB", live?.sinr?.let(::gradeSinr), Modifier.weight(1f))
        FinderMetricTile("RSRQ", live?.rsrq, "dB", live?.rsrq?.let(::gradeRsrq), Modifier.weight(1f))
    }
}

@Composable
private fun FinderMetricTile(label: String, value: Double?, unit: String, grade: Grade?, modifier: Modifier) {
    val bg = grade?.let(::finderGradeColor) ?: MaterialTheme.colorScheme.surfaceVariant
    val fg = grade?.let(::finderOnGradeColor) ?: MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(finderNum(value), fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text(unit, fontSize = 16.sp)
            Text(grade?.let(::finderGradeLabel) ?: "–", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FinderCellCard(live: FinderLive?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val net = live?.netType?.name ?: "–"
            Text("$net  ·  band ${live?.band ?: "–"}  ·  PCI ${live?.pci ?: "–"}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val ta = live?.ta
            Text(
                "Cell ${live?.cellId ?: "–"}  ·  TA ${ta ?: "–"}" + if (ta != null) " (~${ta * FINDER_TA_METERS} m to tower)" else "",
                fontSize = 18.sp,
            )
            val switches = live?.cellSwitches5min ?: 0
            if (switches > 0) {
                Text(
                    "Cell changed $switches× in 5 min: unstable spot",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = FinderWarningRed,
                )
            } else {
                Text("Same cell for the last 5 min", fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun FinderProgressCard(live: FinderLive?, ui: FinderUiState, onCancel: () -> Unit) {
    val progress = live?.progress
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val title = progress?.label ?: when (ui.runningTest) {
                FinderMode.SPOT -> "Spot test"
                FinderMode.SPEED -> "Speed test"
                else -> "Working"
            }
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (ui.runningTest == FinderMode.SPOT) Text("Hold the phone still.", fontSize = 18.sp)
            if (progress != null && progress.totalS > 0) {
                val fraction = (progress.elapsedS.toFloat() / progress.totalS).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(12.dp))
                Text("${progress.elapsedS} / ${progress.totalS} s", fontSize = 18.sp)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(12.dp))
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Cancel test", fontSize = 18.sp)
            }
        }
    }
}

// --- controls ----------------------------------------------------------------------------------

@Composable
private fun FinderControlsCard(
    ui: FinderUiState,
    busy: Boolean,
    onWalk: () -> Unit,
    onMute: () -> Unit,
    onSpotTest: () -> Unit,
    onSpeedTest: () -> Unit,
    onExport: () -> Unit,
    onHeadless: (Boolean) -> Unit,
    onStopAll: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FinderButton(if (ui.walking) "Stop walk" else "Walk", Modifier.weight(1f), filled = !ui.walking, onClick = onWalk)
                FinderButton(if (ui.muted) "Unmute" else "Mute", Modifier.weight(1f), filled = false, onClick = onMute)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FinderButton("Spot test", Modifier.weight(1f), enabled = !busy, onClick = onSpotTest)
                FinderButton("Speed test", Modifier.weight(1f), enabled = !busy, onClick = onSpeedTest)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FinderButton("Export CSV", Modifier.weight(1f), filled = false, onClick = onExport)
                FinderButton("Stop all", Modifier.weight(1f), filled = false, onClick = onStopAll)
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Headless volume keys", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Hold vol+ 1 s: spot test · 3 s: walk on/off\nHold vol− 1 s: speed test · 3 s: stop",
                        fontSize = 15.sp,
                    )
                    val detail = if (ui.headlessEnabled) ui.headlessDetail else null
                    if (detail != null) {
                        Text(
                            detail,
                            fontSize = 14.sp,
                            color = if (ui.headlessListening) MaterialTheme.colorScheme.onSurface else FinderWarningRed,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Switch(checked = ui.headlessEnabled, onCheckedChange = onHeadless)
            }
        }
    }
}

@Composable
private fun FinderButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    color: Color? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val sized = modifier.heightIn(min = 56.dp)
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = sized,
            shape = shape,
            colors = if (color != null) ButtonDefaults.buttonColors(containerColor = color) else ButtonDefaults.buttonColors(),
        ) { Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = sized, shape = shape) {
            Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = color ?: Color.Unspecified)
        }
    }
}

@Composable
private fun FinderMessageCard(ui: FinderUiState, onAddPhoto: (() -> Unit)?) {
    val error = ui.error
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (error != null) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error != null) {
                Text(error, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(ui.message.orEmpty(), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (onAddPhoto != null && ui.message?.startsWith("Saved") == true) {
                    OutlinedButton(onClick = onAddPhoto) { Text("Add photo of this spot", fontSize = 17.sp) }
                }
            }
        }
    }
}

// --- spots -------------------------------------------------------------------------------------

@Composable
private fun FinderSpotCard(
    rank: Int,
    spot: FinderSpot,
    expanded: Boolean,
    busy: Boolean,
    photo: File?,
    onToggle: () -> Unit,
    onSpeedTest: () -> Unit,
    onBaseline: () -> Unit,
    onPhoto: () -> Unit,
    onDelete: () -> Unit,
) {
    val m = spot.metrics
    val scoreGrade = gradeScore(m.score.avg)
    val unstable = SpotCsv.isUnstable(spot)
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#$rank", fontSize = 24.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(10.dp))
                Text(
                    spot.name,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (spot.baseline) FinderBadge("BASELINE", FinderBaselineBlue, Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FinderBadge("${finderNum(m.score.avg)} ${finderGradeLabel(scoreGrade)}", finderGradeColor(scoreGrade), finderOnGradeColor(scoreGrade), fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    spot.bestDownMbps?.let { "↓ ${finderNum(it)} Mbit/s" } ?: "no speed test",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (unstable) {
                    Spacer(Modifier.width(10.dp))
                    FinderBadge("UNSTABLE", FinderWarningRed, Color.White)
                }
            }
            Text(
                "${finderLocalTime(spot.createdAt)} · ${finderOrientationLabel(spot.orientation)} · ${spot.serving.netType.name}" +
                    (spot.serving.band?.let { " B$it" } ?: "") + (spot.serving.pci?.let { " PCI $it" } ?: ""),
                fontSize = 16.sp,
            )
            if (expanded) {
                HorizontalDivider()
                FinderStatLine("Score", m.score, "")
                FinderStatLine("RSRP", m.rsrp, " dBm")
                m.sinr?.let { FinderStatLine("SINR", it, " dB") }
                m.rsrq?.let { FinderStatLine("RSRQ", it, " dB") }
                Text(
                    "Cell ${spot.serving.cellId ?: "–"} · EARFCN ${spot.serving.earfcn ?: "–"} · TA ${spot.serving.ta ?: "–"}" +
                        " · ${spot.cellSwitches} cell switches during the test",
                    fontSize = 16.sp,
                    color = if (unstable) FinderWarningRed else Color.Unspecified,
                )
                Text(
                    "Position ${finderNum(spot.lat, 5)}, ${finderNum(spot.lon, 5)} (±${finderNum(spot.accM, 0)} m) · compass ${finderNum(spot.compassDeg, 0)}°" +
                        " · pitch ${finderNum(spot.pitchDeg, 0)}° roll ${finderNum(spot.rollDeg, 0)}°",
                    fontSize = 16.sp,
                )
                if (spot.speed.isEmpty()) {
                    Text("No speed test yet", fontSize = 16.sp)
                } else {
                    Text("Speed tests", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    spot.speed.asReversed().forEach { FinderSessionLine(it) }
                }
                if (photo != null) FinderSpotPhoto(photo)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FinderButton("Speed test", Modifier.weight(1f), enabled = !busy, onClick = onSpeedTest)
                    FinderButton(
                        if (spot.baseline) "Baseline ✓" else "Set baseline",
                        Modifier.weight(1f),
                        filled = false,
                        enabled = !spot.baseline,
                        onClick = onBaseline,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FinderButton(if (photo != null) "Retake photo" else "Add photo", Modifier.weight(1f), filled = false, onClick = onPhoto)
                    FinderButton("Delete", Modifier.weight(1f), filled = false, color = FinderWarningRed, onClick = onDelete)
                }
            } else {
                Text("Tap for details", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun FinderStatLine(label: String, s: MetricStats, unit: String) {
    Text(
        "$label ${finderNum(s.avg)}$unit  (min ${finderNum(s.min)}, max ${finderNum(s.max)}, ±${finderNum(s.std)}, ${s.samples} samples)",
        fontSize = 17.sp,
    )
}

@Composable
private fun FinderSessionLine(s: SpeedSession) {
    Text(
        "${finderLocalTime(s.at)}${if (s.peakHour) " (peak)" else ""}: ↓ ${finderNum(s.downMbps)}  ↑ ${finderNum(s.upMbps)} Mbit/s · " +
            "ping ${finderNum(s.pingMs, 0)} ms · jitter ${finderNum(s.jitterMs)} ms",
        fontSize = 16.sp,
    )
}

@Composable
private fun FinderSpotPhoto(file: File) {
    var bitmap by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.path) {
        bitmap = withContext(Dispatchers.IO) { loadFinderThumbnail(file, FINDER_THUMBNAIL_PX) }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Photo of the spot",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp)),
        )
    }
}

/** Downsampled, EXIF-rotated photo for the spot card. */
private fun loadFinderThumbnail(file: File, maxPx: Int): ImageBitmap? = runCatching {
    if (!file.exists()) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return@runCatching null
    val degrees = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val oriented = if (degrees == 0f) {
        decoded
    } else {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
    }
    oriented.asImageBitmap()
}.getOrNull()

@Composable
private fun FinderBadge(text: String, background: Color, foreground: Color, fontSize: TextUnit = 14.sp) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = foreground, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}

// --- dialogs -----------------------------------------------------------------------------------

private val FINDER_ORIENTATION_CHOICES: List<Pair<String?, String>> = listOf(
    null to "Auto",
    OrientationMath.UPRIGHT to "Upright",
    OrientationMath.FLAT to "Flat",
    OrientationMath.ROT90 to "Rot 90°",
)

@Composable
private fun FinderSpotTestDialog(
    defaultName: String,
    defaultDurationS: Int,
    onDismiss: () -> Unit,
    onStart: (name: String, orientation: String?, durationS: Int) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var orientation by rememberSaveable { mutableStateOf<String?>(null) }
    var durationText by rememberSaveable { mutableStateOf(defaultDurationS.toString()) }
    val duration = durationText.toIntOrNull()
    val valid = name.isNotBlank() && duration != null && duration in 5..600
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Spot test", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("Name (e.g. Roof NE corner)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Orientation", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FINDER_ORIENTATION_CHOICES.take(2).forEach { (value, label) ->
                        FinderChoiceButton(label, orientation == value, Modifier.weight(1f)) { orientation = value }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FINDER_ORIENTATION_CHOICES.drop(2).forEach { (value, label) ->
                        FinderChoiceButton(label, orientation == value, Modifier.weight(1f)) { orientation = value }
                    }
                }
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { text -> durationText = text.filter { it.isDigit() }.take(3) },
                    label = { Text("Seconds to hold still (5–600)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Auto detects the orientation from the phone's sensors.", fontSize = 14.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onStart(name.trim(), orientation, duration ?: defaultDurationS) }) {
                Text("Start", fontSize = 18.sp)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", fontSize = 18.sp) } },
    )
}

@Composable
private fun FinderChoiceButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, maxLines = 1) }
    }
}

@Composable
private fun FinderSpeedTestDialog(
    spots: List<FinderSpot>,
    runs: Int,
    bytesPerRun: Long,
    onDismiss: () -> Unit,
    onStart: (spotId: String?) -> Unit,
) {
    val newestFirst = remember(spots) { spots.sortedByDescending { Clock.parseIso(it.createdAt) ?: 0L } }
    var selected by rememberSaveable { mutableStateOf(newestFirst.firstOrNull()?.id) }
    val megabytes = runs * bytesPerRun / 1_000_000.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speed test", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$runs runs, about ${finderNum(megabytes, 0)} MB of mobile data.", fontSize = 17.sp)
                Text("Attach the result to:", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    itemsIndexed(newestFirst.take(FINDER_MAX_SPEED_TARGETS), key = { _, s -> s.id }) { _, spot ->
                        FinderTargetRow(spot.name, selected == spot.id) { selected = spot.id }
                    }
                    item { FinderTargetRow("No spot (just measure)", selected == null) { selected = null } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(selected) }) { Text("Start", fontSize = 18.sp) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", fontSize = 18.sp) } },
    )
}

@Composable
private fun FinderTargetRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val FINDER_TA_METERS = 78
private const val FINDER_THUMBNAIL_PX = 720
private const val FINDER_MAX_SPEED_TARGETS = 20

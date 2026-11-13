package com.example.fittrack

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.fittrack.data.AppDatabase
import com.example.fittrack.data.Workout
import com.example.fittrack.tracking.TrackingService
import com.example.fittrack.tracking.TrackingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { FitApp() }
            }
        }
    }
}

@Composable
fun FitApp() {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Track") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History") })
        }
        if (tab == 0) TrackerScreen() else HistoryScreen()
    }
}

@Composable
fun TrackerScreen() {
    val ctx = LocalContext.current
    val tracking by TrackingState.isTracking.collectAsStateWithLifecycle()
    val steps by TrackingState.steps.collectAsStateWithLifecycle()
    val distance by TrackingState.distanceMeters.collectAsStateWithLifecycle()
    val points by TrackingState.points.collectAsStateWithLifecycle()
    val startTime by TrackingState.startTime.collectAsStateWithLifecycle()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tracking) {
        while (tracking) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val permissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    fun hasLocation() = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (hasLocation()) TrackingService.start(ctx) }

    val elapsed = if (tracking) now - startTime else 0L

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Steps", steps.toString(), Modifier.weight(1f))
            StatCard("Distance", "%.2f km".format(distance / 1000f), Modifier.weight(1f))
            StatCard("Time", formatDuration(elapsed), Modifier.weight(1f))
        }

        Card(Modifier.fillMaxWidth().weight(1f)) {
            RouteView(
                points.map { it.latitude to it.longitude },
                Modifier.fillMaxSize().padding(16.dp)
            )
        }

        Button(
            onClick = {
                if (tracking) TrackingService.stop(ctx)
                else if (hasLocation()) TrackingService.start(ctx)
                else launcher.launch(permissions)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(if (tracking) "Stop workout" else "Start workout")
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(
            Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val dao = remember { AppDatabase.get(ctx).dao() }
    val workouts by dao.all().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }

    if (workouts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No workouts yet")
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(workouts, key = { it.id }) { w: Workout ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(fmt.format(Date(w.startTime)), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "%.2f km  •  %d steps  •  %s".format(
                            w.distanceMeters / 1000f, w.steps,
                            formatDuration(w.endTime - w.startTime)
                        )
                    )
                    RouteView(parseRoute(w.route), Modifier.fillMaxWidth().height(140.dp))
                    TextButton(onClick = { scope.launch { dao.delete(w) } }) { Text("Delete") }
                }
            }
        }
    }
}

/** Draws a route as a line, scaled to fit. No map key needed. */
@Composable
fun RouteView(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }
        val maxLon = points.maxOf { it.second }
        val lonFactor = cos(Math.toRadians((minLat + maxLat) / 2)).coerceAtLeast(0.01)
        val spanX = (maxLon - minLon) * lonFactor
        val spanY = maxLat - minLat
        val span = max(spanX, spanY).coerceAtLeast(1e-6)
        val scale = (min(size.width, size.height) * 0.9f) / span.toFloat()
        val offX = (size.width - (spanX * scale).toFloat()) / 2f
        val offY = (size.height - (spanY * scale).toFloat()) / 2f

        fun map(p: Pair<Double, Double>) = Offset(
            offX + ((p.second - minLon) * lonFactor * scale).toFloat(),
            offY + ((maxLat - p.first) * scale).toFloat()
        )

        val path = Path()
        points.forEachIndexed { i, p ->
            val o = map(p)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, lineColor, style = Stroke(width = 6f))
        drawCircle(dotColor, radius = 12f, center = map(points.last()))
    }
}

fun parseRoute(route: String): List<Pair<Double, Double>> =
    if (route.isBlank()) emptyList()
    else route.split(";").mapNotNull {
        val p = it.split(",")
        val lat = p.getOrNull(0)?.toDoubleOrNull()
        val lon = p.getOrNull(1)?.toDoubleOrNull()
        if (lat != null && lon != null) lat to lon else null
    }

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

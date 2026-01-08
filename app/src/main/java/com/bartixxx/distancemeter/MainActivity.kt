package com.bartixxx.distancemeter

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource 
import com.bartixxx.distancemeter.ui.theme.DistanceTheme
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.*
import kotlin.math.atan2
import kotlin.math.abs

class MainActivity : ComponentActivity(), SensorEventListener {

    private val TAG = "Rangefinder"
    private lateinit var sensorManager: SensorManager
    private var gravitySensor: Sensor? = null
    
    // Tilt state (degrees)
    private var _pitch by mutableStateOf(0.0) // Up/Down tilt
    private var lastLogTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setContent {
            DistanceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MainContent()
                        LevelOverlay(pitch = _pitch)
                    }
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_ACCELEROMETER || it.sensor.type == Sensor.TYPE_GRAVITY) {
                val x = it.values[0]
                val y = it.values[1]
                val z = it.values[2]
                
                // Calculate Pitch (Tilt up/down)
                val anglePitch = atan2(z.toDouble(), Math.sqrt((x * x + y * y).toDouble())) * (180 / Math.PI)
                _pitch = anglePitch

                // Log pitch every 1000ms
                val now = System.currentTimeMillis()
                if (now - lastLogTime > 1000) {
                    Log.d(TAG, "Tilt Pitch: $anglePitch")
                    lastLogTime = now
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@Composable
fun LevelOverlay(pitch: Double) {
    val isLevelPitch = abs(pitch) < 2.0 
    val pitchColor = if (isLevelPitch) Color.Green else Color.Gray.copy(alpha = 0.5f)
    val angleFormat = stringResource(R.string.angle_format)
    
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = center
        
        // --- PITCH (Vertical Tilt) ---
        val pitchOffset = (pitch * 15).coerceIn(-400.0, 400.0).toFloat()
        val bracketColor = if (isLevelPitch) Color.Green else Color.Red
        
        // Fixed Center Target
        drawLine(
            color = Color.White.copy(alpha = 0.5f),
            start = Offset(center.x - 60, center.y),
            end = Offset(center.x + 60, center.y),
            strokeWidth = 4f
        )
        
        // Moving Pitch Indicator
        val indicatedY = center.y + pitchOffset
        drawLine(
            color = bracketColor,
            start = Offset(center.x - 40, indicatedY),
            end = Offset(center.x + 40, indicatedY),
            strokeWidth = 8f
        )
        
        // Vertical Guide Line (faded)
        drawLine(
            color = Color.White.copy(alpha = 0.1f),
            start = Offset(center.x, center.y - 400),
            end = Offset(center.x, center.y + 400),
            strokeWidth = 2f
        )
    }
    
    // Text Readout for Angle
    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = 120.dp),
        contentAlignment = Alignment.Center
    ) {
       Column(horizontalAlignment = Alignment.CenterHorizontally) {
           Spacer(modifier = Modifier.height(350.dp))
           Text(
               text = angleFormat.format(pitch),
               color = if (isLevelPitch) Color.Green else Color.White,
               fontSize = 18.sp,
               fontWeight = FontWeight.Bold
           )
       }
    }
}

@Composable
fun MainContent() {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        RangefinderScreen(Modifier.padding(innerPadding))
    }
}

@Composable
fun RangefinderScreen(modifier: Modifier = Modifier) {
    var isRunning by remember { mutableStateOf(false) }
    var distanceMm by remember { mutableStateOf(0.0) } 
    // Status text logic needs to be dynamic too
    // We'll store status INT or KEY, and translate in UI
    var rawStatus by remember { mutableIntStateOf(0) }
    var runningStateText by remember { mutableStateOf<Int?>(null) } // Resource ID for "Stopped" etc
    
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val TAG = "RangefinderUI"
    val context = androidx.compose.ui.platform.LocalContext.current
    val vibrator = remember {
        context.getSystemService(android.os.Vibrator::class.java)
    }
    
    fun vibrateSingle() {
        vibrator?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }
    
    fun vibrateTriple() {
        // "Puk puk puk" - fast pattern
        val timings = longArrayOf(0, 30, 50, 30, 50, 30) 
        vibrator?.vibrate(android.os.VibrationEffect.createWaveform(timings, -1))
    }

    var savedMeasurements = remember { mutableStateListOf<Double>() }
    val displayList = savedMeasurements.asReversed()
    
    val statusText = if (runningStateText != null) {
        stringResource(runningStateText!!)
    } else {
        // User request: Show errors ONLY if no reception (distance invalid).
        // If we have a valid distance (> 0), treat it as "Ready" despite codes 2, 4, 5, 7, 12 etc.
        // We assume 0.0 is "no measurement" or error state.
        if (distanceMm > 0.0) {
            stringResource(R.string.status_ready)
        } else {
            when (rawStatus) {
                2 -> stringResource(R.string.status_signal_fail)
                4 -> stringResource(R.string.status_phase_fail)
                7 -> stringResource(R.string.status_wrapper_fail)
                12 -> stringResource(R.string.status_poor_signal)
                5 -> "Hardware/Range Fail" // Mapping unknown status 5
                0 -> stringResource(R.string.status_ready) // Default 0 to Ready
                else -> "Code: $rawStatus"
            }
        }
    }

    // Color logic: Green (Ready), Yellow (Warning/Poor), Red (Error)
    // If OutOfRange -> Red
    // If Range OK -> Green (Primary), unless status 12 (Yellow/Poor)
    val signalColor = if (distanceMm <= 0.0) {
        MaterialTheme.colorScheme.error 
    } else {
        if (rawStatus == 12) Color(0xFFFFC107) else MaterialTheme.colorScheme.primary
    }
    
    // Watchdog state
    var lastDataTimestamp by remember { mutableStateOf(0L) }

    // WATCHDOG LOOP: Check every 200ms if data is stale (>500ms)
    LaunchedEffect(isRunning) {
        if (isRunning) {
            while (isActive) {
                delay(200)
                val now = System.currentTimeMillis()
                // If running, but no data for 500ms, assume Out of Range / Sensor Blocked
                if (now - lastDataTimestamp > 500 && lastDataTimestamp > 0) {
                     // Force update to "Out of Range" state
                     distanceMm = 0.0 
                     // We don't change rawStatus status here necessarily, or we could set it to error
                }
            }
        }
    }
    // Check for "Out of Range" (0 or negative) - user visualization request
    val isOutOfRange = distanceMm <= 0.0

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ... (rest of UI)
        
        // --- MAIN READOUT ---
        Spacer(modifier = Modifier.weight(1f)) // Push to center-top
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isOutOfRange) {
                // VISUALIZATION: Out of Range / Infinity
                Icon(
                    imageVector = Icons.Filled.Info, 
                    contentDescription = "Out of Range",
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "---",
                    fontSize = 64.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // REMOVED Warning Icon for valid measurements to reduce clutter
                    Text(
                        text = "%.1f".format(distanceMm / 10.0),
                        fontSize = 96.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = signalColor,
                        lineHeight = 100.sp
                    )
                }
                Text(
                    text = stringResource(R.string.unit_cm),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = signalColor
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (rawStatus == 0 || rawStatus == 12) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = "${stringResource(R.string.label_status)}: $statusText",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
        Spacer(modifier = Modifier.weight(1f))

        // --- CONTROLS ---
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            // SCAN / STOP
            Button(
                onClick = {
                    if (isRunning) {
                        // STOPPING -> Single Puk
                        vibrateSingle()
                        isRunning = false
                        job?.cancel()
                        job = null
                        Log.i(TAG, "Stopping Sensor Loop")
                        scope.launch(Dispatchers.IO) { disableSensor() }
                        runningStateText = R.string.status_stopped
                    } else {
                        // STARTING -> Triple Puk Puk Puk
                        vibrateTriple()
                        isRunning = true
                        runningStateText = null // Use rawStatus
                        job = scope.launch(Dispatchers.IO) {
                            Log.i(TAG, "Starting Sensor Loop")
                            runSensorLoop { dist, stat, msg ->
                                distanceMm = dist
                                rawStatus = stat
                                lastDataTimestamp = System.currentTimeMillis() // Update watchdog
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f).height(64.dp),
                colors = if (isRunning) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = if (isRunning) stringResource(R.string.btn_stop) else stringResource(R.string.btn_scan),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isRunning) stringResource(R.string.btn_stop) else stringResource(R.string.btn_scan), 
                    fontSize = 18.sp, 
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            // SAVE
            OutlinedButton(
                onClick = {
                    vibrateSingle()
                    Log.d(TAG, "Saving measurement: $distanceMm")
                    savedMeasurements.add(distanceMm)
                },
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                 Icon(Icons.Default.Add, contentDescription = stringResource(R.string.btn_save))
                 Spacer(Modifier.width(8.dp))
                 Text(stringResource(R.string.btn_save))
            }
            
            // CLEAR
            OutlinedButton(
                onClick = {
                    vibrateSingle()
                    Log.d(TAG, "Clearing history")
                    savedMeasurements.clear()
                },
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                 Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.btn_clear))
                 Spacer(Modifier.width(8.dp))
                 Text(stringResource(R.string.btn_clear))
            }
        }

        // --- CALCULATION RESULTS (Animated) ---
        AnimatedVisibility(
            visible = savedMeasurements.size >= 2,
            enter = fadeIn(animationSpec = tween(500)),
            exit = fadeOut(animationSpec = tween(300))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // AREA (2+ items)
                if (savedMeasurements.size >= 2) {
                    val last = savedMeasurements.last()
                    val prev = savedMeasurements[savedMeasurements.size - 2]
                    val areaM2 = (last / 1000.0) * (prev / 1000.0)
                    
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(R.string.area_label), style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = "%.3f %s".format(areaM2, stringResource(R.string.unit_m2)), 
                                fontSize = 32.sp, 
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                // VOLUME (3+ items)
                if (savedMeasurements.size >= 3) {
                     val last = savedMeasurements.last()
                     val prev = savedMeasurements[savedMeasurements.size - 2]
                     val prev2 = savedMeasurements[savedMeasurements.size - 3]
                     val volM3 = (last / 1000.0) * (prev / 1000.0) * (prev2 / 1000.0)
                     
                     Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(R.string.volume_label), style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = "%.3f %s".format(volM3, stringResource(R.string.unit_m3)), 
                                fontSize = 32.sp, 
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
        
        if (savedMeasurements.size < 2) {
             Spacer(modifier = Modifier.height(24.dp)) // Placeholder height to prevent jarring jump
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        
        // --- HISTORY ---
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(0.8f), // Give less weight to history list
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(displayList.size) { index ->
                val measure = displayList[index]
                ListItem(
                    headlineContent = { Text("%.1f %s".format(measure / 10.0, stringResource(R.string.unit_cm)), fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                    overlineContent = { Text("${stringResource(R.string.measurement_prefix)}${savedMeasurements.size - index}") },
                    trailingContent = { Text("%.0f %s".format(measure, stringResource(R.string.unit_mm)), style = MaterialTheme.typography.bodySmall) }
                )
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
            }
        }
    }
}

// Shell Functions
fun disableSensor() {
    try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo 0 > /sys/class/input/input11/enable_ps_sensor"))
        p.waitFor()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

suspend fun runSensorLoop(onUpdate: (Double, Int, String) -> Unit) {
    try {
        Runtime.getRuntime().exec(arrayOf("su", "-c", "echo 1 > /sys/class/input/input11/enable_ps_sensor")).waitFor()

        // Read stream
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "getevent -l /dev/input/event11"))
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val TAG = "RangefinderLoop"

    // Filter state
        var lastStableStatus = 0
        var pendingStatus = -1
        var consecutiveCount = 0
        val STABILITY_THRESHOLD = 5
        
        // Smoothing state
        val smoothingBuffer = ArrayList<Int>()

        var line: String?
        while (currentCoroutineContext().isActive) {
            line = reader.readLine()
            if (line == null) break
            
            // Parse line: e.g. "EV_ABS       ABS_HAT1X            00920002"
            if (line.contains("ABS_HAT1X")) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 3) {
                    val hex = parts.last() // "00920002"
                    try {
                        val value = hex.toLong(16)
                        
                        // DEBUG: Log raw values for analysis
                        Log.i(TAG, "RAW_READING: $hex | Val: $value")

                        // Upper 16 = mm
                        val mm = (value ushr 16).toInt()
                        // Lower 16 = status
                        val currentStatus = (value and 0xFFFF).toInt()
                        
                        // --- SMOOTHING FILTER (N=10) ---
                        smoothingBuffer.add(mm)
                        if (smoothingBuffer.size > 10) {
                            smoothingBuffer.removeAt(0)
                        }
                        val smoothedMm = smoothingBuffer.average()
                        // -------------------------------
                        
                        // --- STABILITY FILTER ---
                        if (currentStatus == pendingStatus) {
                            consecutiveCount++
                        } else {
                            pendingStatus = currentStatus
                            consecutiveCount = 1
                        }
                        
                        if (consecutiveCount >= STABILITY_THRESHOLD) {
                            lastStableStatus = currentStatus
                        }
                        // ------------------------
                        
                        var statStr = "OK" // Unused in UI now, but kept for signature
                        
                        withContext(Dispatchers.Main) {
                            // Send 'smoothedMm' (averaged) and 'lastStableStatus' (filtered)
                            onUpdate(smoothedMm, lastStableStatus, statStr)
                        }
                    } catch (e: Exception) {
                         // ignore parse err
                    }
                }
            }
        }
        process.destroy()
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            onUpdate(0.0, -1, "Error: ${e.message}")
        }
    } finally {
        disableSensor()
    }
}
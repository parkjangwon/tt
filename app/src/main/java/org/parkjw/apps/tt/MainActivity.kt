package org.parkjw.apps.tt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TTTheme {
                AppScreen()
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = Settings())

    var overlayGranted by remember { mutableStateOf(AndroidSettings.canDrawOverlays(context)) }
    var batteryExempt by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
    var notificationsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var pickerOpen by remember { mutableStateOf(false) }
    var pendingShortcutApp by remember { mutableStateOf<AppEntry?>(null) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationsGranted = granted }

    // Re-read permission state when returning from system settings screens.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = AndroidSettings.canDrawOverlays(context)
                batteryExempt = context.isIgnoringBatteryOptimizations()
                notificationsGranted =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Live hinge angle readout for tuning the thresholds.
    val hingeSensor = remember { context.sensorManager()?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) }
    var hingeAngle by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(hingeSensor) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                hingeAngle = event.values[0]
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        hingeSensor?.let {
            context.sensorManager()?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { context.sensorManager()?.unregisterListener(listener) }
    }

    fun setEnabled(on: Boolean) {
        scope.launch { repo.setEnabled(on) }
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            ContextCompat.startForegroundService(context, Intent(context, HingeService::class.java))
        } else {
            context.stopService(Intent(context, HingeService::class.java))
        }
    }

    fun grantOverlay() {
        context.startActivity(
            Intent(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    fun requestBatteryExemption() {
        context.startActivity(
            Intent(
                AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    fun testAction() {
        val intent = ActionLauncher.buildIntent(context, settings) ?: return
        runCatching { context.startActivity(intent) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("TT", fontWeight = FontWeight.Bold) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Bend your foldable past the fold angle, then reopen it quickly — TT launches the app or link you pick below. Works on any device with a hinge angle sensor (Galaxy Z Fold, Pixel Fold, …).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Enabled", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                !settings.enabled -> "Off"
                                !settings.isActionReady -> "On — pick an app or link to arm"
                                else -> "On — ${ActionLauncher.describe(settings)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { setEnabled(it) }
                    )
                }
            }

            if (settings.enabled && !overlayGranted) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            "TT can't launch apps from the background yet — grant \u201cDisplay over other apps\u201d.",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = { grantOverlay() }) { Text("Grant") }
                    }
                }
            }

            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Setup", style = MaterialTheme.typography.titleMedium)
                    SetupRow(
                        ok = overlayGranted,
                        okIcon = Icons.Rounded.Check,
                        pendingIcon = Icons.Rounded.Warning,
                        title = "Display over other apps",
                        description = "Required so TT can open your app from the background",
                        actionLabel = "Grant",
                        onAction = { grantOverlay() },
                    )
                    SetupRow(
                        ok = notificationsGranted,
                        okIcon = Icons.Rounded.Check,
                        pendingIcon = Icons.Rounded.Notifications,
                        title = "Notifications",
                        description = "Shows a quiet notification while TT is listening",
                        actionLabel = "Allow",
                        onAction = {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                    )
                    SetupRow(
                        ok = batteryExempt,
                        okIcon = Icons.Rounded.Check,
                        pendingIcon = Icons.Rounded.Info,
                        title = "Battery optimization",
                        description = "Exempt TT so Android doesn't kill the listener",
                        actionLabel = "Exempt",
                        onAction = { requestBatteryExemption() },
                    )
                    SetupRow(
                        ok = hingeSensor != null,
                        okIcon = Icons.Rounded.Check,
                        pendingIcon = Icons.Rounded.Info,
                        title = "Hinge angle sensor",
                        description = if (hingeSensor != null) {
                            "Available — this device is supported"
                        } else {
                            "Not found on this device"
                        },
                        actionLabel = null,
                        onAction = null,
                    )
                }
            }

            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("When triggered", style = MaterialTheme.typography.titleMedium)

                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = settings.actionType == ActionType.APP,
                            onClick = { scope.launch { repo.setActionType(ActionType.APP) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text("App") }
                        SegmentedButton(
                            selected = settings.actionType == ActionType.SHORTCUT,
                            onClick = { scope.launch { repo.setActionType(ActionType.SHORTCUT) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text("Shortcut") }
                    }

                    when (settings.actionType) {
                        ActionType.APP -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { pickerOpen = true }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    if (settings.packageName.isBlank()) "Choose an app…"
                                    else settings.appLabel.ifBlank { settings.packageName },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (settings.packageName.isNotBlank()) {
                                    Text(
                                        settings.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        ActionType.SHORTCUT -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { pickerOpen = true }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    if (settings.shortcutId.isBlank()) "Choose a shortcut…"
                                    else settings.shortcutLabel.ifBlank { settings.shortcutId },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (settings.shortcutId.isNotBlank()) {
                                    Text(
                                        settings.shortcutPackage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            enabled = settings.isActionReady,
                            onClick = { testAction() }
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Test now")
                        }
                        Spacer(Modifier.size(12.dp))
                        Text(
                            "Runs the configured action immediately.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Hinge", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        hingeAngle?.let { angle ->
                            val phase = when {
                                angle >= GestureConfig.STANDARD.openAngle -> "Open"
                                angle <= GestureConfig.STANDARD.foldAngle -> "Bent"
                                else -> "Partial"
                            }
                            Text(
                                phase,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (hingeSensor == null) {
                        Text(
                            "No hinge angle sensor found. TT needs a foldable such as Galaxy Z Fold or Pixel Fold.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            hingeAngle?.let { "${it.roundToInt()}\u00b0" } ?: "…",
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Text(
                            "Live reading. Gesture: open (180\u00b0) \u2192 bend past 90\u00b0 \u2192 reopen within 2s.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickerOpen) {
        AppPickerDialog(
            onDismiss = { pickerOpen = false },
            onPick = { entry ->
                pickerOpen = false
                if (settings.actionType == ActionType.SHORTCUT) {
                    pendingShortcutApp = entry
                } else {
                    scope.launch { repo.setApp(entry.packageName, entry.label) }
                }
            }
        )
    }

    pendingShortcutApp?.let { app ->
        ShortcutPickerDialog(
            app = app,
            onDismiss = { pendingShortcutApp = null },
            onPick = { shortcut ->
                pendingShortcutApp = null
                scope.launch {
                    repo.setShortcut(app.packageName, app.label, shortcut.id, shortcut.label)
                }
            }
        )
    }
}

@Composable
private fun SetupRow(
    ok: Boolean,
    okIcon: ImageVector,
    pendingIcon: ImageVector,
    title: String,
    description: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (ok) okIcon else pendingIcon,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!ok && actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ShortcutPickerDialog(
    app: AppEntry,
    onDismiss: () -> Unit,
    onPick: (ShortcutReader.AppShortcut) -> Unit,
) {
    val context = LocalContext.current
    var shortcuts by remember { mutableStateOf<List<ShortcutReader.AppShortcut>?>(null) }

    LaunchedEffect(app.packageName) {
        shortcuts = withContext(Dispatchers.IO) {
            ShortcutReader.shortcutsFor(context, app.packageName)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Shortcuts · ${app.label}", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Only shortcuts the app declares statically (manifest shortcuts) are visible to other apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (val list = shortcuts) {
                    null -> Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    else -> if (list.isEmpty()) {
                        Text(
                            "This app publishes no shortcuts.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.height(360.dp)) {
                            items(list, key = { it.id }) { sc ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPick(sc) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val icon = remember(sc.id) { sc.icon?.toIconBitmap(48) }
                                    if (icon != null) {
                                        Image(icon, contentDescription = null, modifier = Modifier.size(36.dp))
                                        Spacer(Modifier.size(12.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(sc.label, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            sc.id,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: androidx.compose.ui.graphics.ImageBitmap,
)

@Composable
private fun AppPickerDialog(
    onDismiss: () -> Unit,
    onPick: (AppEntry) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Choose an app", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = null,
                                modifier = Modifier.clickable { query = "" }
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (val list = apps) {
                    null -> Box(
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    else -> {
                        val filtered = list.filter {
                            it.label.contains(query, ignoreCase = true) ||
                                it.packageName.contains(query, ignoreCase = true)
                        }
                        if (filtered.isEmpty()) {
                            Text(
                                "No apps found",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                        LazyColumn(modifier = Modifier.height(400.dp)) {
                            items(filtered, key = { it.packageName }) { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPick(entry) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Image(entry.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                                    Spacer(Modifier.size(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            entry.label,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            entry.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val fallback = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
    return pm.queryIntentActivities(intent, 0)
        .asSequence()
        .filter { it.activityInfo != null && it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map { ri ->
            val label = runCatching { ri.loadLabel(pm).toString() }
                .getOrDefault(ri.activityInfo.packageName)
            val icon = runCatching { ri.loadIcon(pm).toIconBitmap(96) }
                .getOrDefault(fallback)
            AppEntry(label, ri.activityInfo.packageName, icon)
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}

private fun Drawable.toIconBitmap(size: Int): androidx.compose.ui.graphics.ImageBitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bitmap.asImageBitmap()
}

private fun Context.sensorManager(): SensorManager? =
    getSystemService(SensorManager::class.java)

private fun Context.isIgnoringBatteryOptimizations(): Boolean {
    val pm = getSystemService(PowerManager::class.java) ?: return true
    return pm.isIgnoringBatteryOptimizations(packageName)
}

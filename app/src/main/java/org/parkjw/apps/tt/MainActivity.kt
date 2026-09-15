package org.parkjw.apps.tt

import android.Manifest
import android.app.Activity
import android.app.LocaleManager
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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

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
        scope.launch {
            // Persist first, then start the service — the service reads the setting on
            // startup and a stale read would stopSelf() ahead of startForeground (crash).
            repo.setEnabled(on)
            if (on) {
                ContextCompat.startForegroundService(context, Intent(context, HingeService::class.java))
            } else {
                context.stopService(Intent(context, HingeService::class.java))
            }
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
        ActionLauncher.buildIntent(context, settings)?.let { runCatching { context.startActivity(it) } }
    }

    fun setLanguage(tag: String) {
        scope.launch { repo.setLanguage(tag) }
        AppLocales.applySystem(context, tag)
        if (Build.VERSION.SDK_INT < 33) (context as? Activity)?.recreate()
    }

    // Source of truth for the selected language: the system on 13+, DataStore below.
    val selectedTag = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java)?.applicationLocales
            ?.toLanguageTags().orEmpty()
    } else {
        settings.language
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ttColors().background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // Viewing area — title and summary, no interactive elements.
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text("TT", fontSize = 40.sp, fontWeight = FontWeight.Light, color = ttColors().onBackground)
            Text(stringResource(R.string.subtitle), fontSize = 16.sp, color = ttColors().subText)
        }

        // Master switch
        TtRow(
            title = stringResource(R.string.enabled),
            description = when {
                !settings.enabled -> stringResource(R.string.enabled_off)
                !settings.isActionReady -> stringResource(R.string.enabled_on_unarmed)
                else -> stringResource(R.string.enabled_on, ActionLauncher.describe(settings))
            },
            trailing = { TtSwitch(checked = settings.enabled, onCheckedChange = { setEnabled(it) }) }
        )
        TtDivider()

        if (settings.enabled && !overlayGranted) {
            TtRow(
                title = stringResource(R.string.permission_required_title),
                description = stringResource(R.string.permission_overlay_desc),
                leading = {
                    Icon(
                        Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                trailing = {
                    TtTextButton(stringResource(R.string.action_grant), onClick = { grantOverlay() })
                },
            )
            TtDivider()
        }

        SectionHeader(stringResource(R.string.setup))
        SetupRow(
            ok = overlayGranted,
            pendingIcon = Icons.Rounded.Warning,
            title = stringResource(R.string.overlay_title),
            description = stringResource(R.string.overlay_desc),
            actionLabel = if (overlayGranted) null else stringResource(R.string.action_grant),
            onAction = { grantOverlay() },
        )
        SetupRow(
            ok = notificationsGranted,
            pendingIcon = Icons.Rounded.Notifications,
            title = stringResource(R.string.notifications_title),
            description = stringResource(R.string.notifications_desc),
            actionLabel = if (notificationsGranted) null else stringResource(R.string.action_allow),
            onAction = {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
        )
        SetupRow(
            ok = batteryExempt,
            pendingIcon = Icons.Rounded.Info,
            title = stringResource(R.string.battery_title),
            description = stringResource(R.string.battery_desc),
            actionLabel = if (batteryExempt) null else stringResource(R.string.action_exempt),
            onAction = { requestBatteryExemption() },
        )
        SetupRow(
            ok = hingeSensor != null,
            pendingIcon = Icons.Rounded.Info,
            title = stringResource(R.string.hinge_sensor_title),
            description = if (hingeSensor != null) stringResource(R.string.hinge_sensor_ok)
            else stringResource(R.string.hinge_sensor_none),
            actionLabel = null,
            onAction = null,
        )
        TtDivider()

        SectionHeader(stringResource(R.string.when_triggered))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ToggleOption(
                text = stringResource(R.string.type_app),
                selected = settings.actionType == ActionType.APP,
                modifier = Modifier.weight(1f),
                onClick = { scope.launch { repo.setActionType(ActionType.APP) } }
            )
            ToggleOption(
                text = stringResource(R.string.type_shortcut),
                selected = settings.actionType == ActionType.SHORTCUT,
                modifier = Modifier.weight(1f),
                onClick = { scope.launch { repo.setActionType(ActionType.SHORTCUT) } }
            )
        }
        when (settings.actionType) {
            ActionType.APP -> TtRow(
                title = if (settings.packageName.isBlank()) stringResource(R.string.choose_app)
                else settings.appLabel.ifBlank { settings.packageName },
                description = settings.packageName.takeIf { it.isNotBlank() },
                onClick = { pickerOpen = true },
            )

            ActionType.SHORTCUT -> TtRow(
                title = if (settings.shortcutId.isBlank()) stringResource(R.string.choose_shortcut)
                else settings.shortcutLabel.ifBlank { settings.shortcutId },
                description = settings.shortcutPackage.takeIf { it.isNotBlank() },
                onClick = { pickerOpen = true },
            )
        }
        TtDivider()

        SectionHeader(stringResource(R.string.hinge))
        if (hingeSensor == null) {
            TtRow(
                title = stringResource(R.string.hinge_na),
                description = stringResource(R.string.hinge_none_desc),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    hingeAngle?.let { "${it.roundToInt()}\u00b0" } ?: "…",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Light,
                    color = ttColors().onBackground,
                    modifier = Modifier.weight(1f)
                )
                hingeAngle?.let { angle ->
                    val phase = when {
                        angle >= GestureConfig.STANDARD.openAngle -> stringResource(R.string.phase_open)
                        angle <= GestureConfig.STANDARD.foldAngle -> stringResource(R.string.phase_bent)
                        else -> stringResource(R.string.phase_partial)
                    }
                    Text(phase, fontSize = 17.sp, color = ttColors().accent)
                }
            }
            Text(
                stringResource(R.string.gesture_hint),
                fontSize = 13.sp,
                color = ttColors().subText,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        SectionHeader(stringResource(R.string.language))
        LanguageRow(stringResource(R.string.lang_system), selectedTag.isBlank()) { setLanguage("") }
        LanguageRow("English", selectedTag == "en") { setLanguage("en") }
        LanguageRow("한국어", selectedTag == "ko") { setLanguage("ko") }
        LanguageRow("日本語", selectedTag == "ja") { setLanguage("ja") }
        LanguageRow("中文", selectedTag == "zh") { setLanguage("zh") }

        // Interaction area — the primary action lives at the bottom.
        Box(Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
            TtButton(
                text = stringResource(R.string.test_now),
                enabled = settings.isActionReady,
                onClick = { testAction() }
            )
        }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
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

// --- sections ---------------------------------------------------------------

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = ttColors().accent,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 6.dp)
    )
}

@Composable
private fun TtDivider() {
    HorizontalDivider(color = ttColors().divider)
}

@Composable
private fun TtRow(
    title: String,
    description: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        if (leading != null) Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 18.sp,
                color = if (titleColor == Color.Unspecified) ttColors().onBackground else titleColor
            )
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(description, fontSize = 13.sp, color = ttColors().subText)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.size(12.dp))
            trailing()
        }
    }
}

@Composable
private fun LanguageRow(label: String, selected: Boolean, onClick: () -> Unit) {
    TtRow(
        title = label,
        onClick = onClick,
        trailing = {
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = ttColors().activated
                )
            }
        }
    )
}

@Composable
private fun SetupRow(
    ok: Boolean,
    pendingIcon: ImageVector,
    title: String,
    description: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    TtRow(
        title = title,
        description = description,
        leading = {
            Icon(
                if (ok) Icons.Rounded.Check else pendingIcon,
                contentDescription = null,
                tint = if (ok) ttColors().activated else ttColors().subText
            )
        },
        trailing = {
            if (!ok && actionLabel != null && onAction != null) {
                TtTextButton(actionLabel, onClick = onAction)
            }
        }
    )
}

@Composable
private fun TtSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = ttColors().activated,
            uncheckedThumbColor = ttColors().subText,
            uncheckedTrackColor = ttColors().grayButton,
            uncheckedBorderColor = Color.Transparent,
        )
    )
}

@Composable
private fun ToggleOption(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) ttColors().accent else ttColors().grayButton)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 17.sp,
            color = if (selected) ttColors().onAccent else ttColors().onBackground
        )
    }
}

@Composable
private fun TtButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) ttColors().accent else ttColors().grayButton)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 17.sp,
            color = if (enabled) ttColors().onAccent else ttColors().subText
        )
    }
}

@Composable
private fun TtTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(text, fontSize = 17.sp) }
}

// --- pickers (bottom sheets) --------------------------------------------------

private data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: androidx.compose.ui.graphics.ImageBitmap,
)

@OptIn(ExperimentalMaterial3Api::class)
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = ttColors().background,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.picker_choose_app), fontSize = 20.sp, fontWeight = FontWeight.Medium)
            TtSearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth()
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
                            stringResource(R.string.no_apps),
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
                                Image(
                                    entry.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.label,
                                        fontSize = 18.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        entry.packageName,
                                        fontSize = 13.sp,
                                        color = ttColors().subText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = ttColors().background,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.shortcuts_title), fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(
                app.label,
                fontSize = 13.sp,
                color = ttColors().subText
            )
            Text(
                stringResource(R.string.shortcuts_note),
                fontSize = 13.sp,
                color = ttColors().subText
            )
            when (val list = shortcuts) {
                null -> Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                else -> if (list.isEmpty()) {
                    Text(
                        stringResource(R.string.no_shortcuts),
                        fontSize = 18.sp,
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
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val icon = remember(sc.id) { sc.icon?.toIconBitmap(48) }
                                if (icon != null) {
                                    Image(icon, contentDescription = null, modifier = Modifier.size(36.dp))
                                    Spacer(Modifier.size(12.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(sc.label, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        sc.id,
                                        fontSize = 13.sp,
                                        color = ttColors().subText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun TtSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        placeholder = { Text(stringResource(R.string.search), fontSize = 17.sp) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = ttColors().grayButton,
            unfocusedContainerColor = ttColors().grayButton,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth()
    )
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

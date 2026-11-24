package com.dx9901mk5.pixelvoiceime.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dx9901mk5.pixelvoiceime.PixelVoiceApp
import com.dx9901mk5.pixelvoiceime.R
import com.dx9901mk5.pixelvoiceime.billing.ProAccessState
import com.dx9901mk5.pixelvoiceime.billing.ProFeature
import com.dx9901mk5.pixelvoiceime.data.AppUiLanguage
import com.dx9901mk5.pixelvoiceime.ime.FloatingMicService
import com.dx9901mk5.pixelvoiceime.ime.PixelVoiceAccessibilityService
import com.dx9901mk5.pixelvoiceime.ui.theme.PixelVoiceTheme
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as PixelVoiceApp

        if (app.prefs.config.value.floatingBallEnabled && Settings.canDrawOverlays(this)) {
            FloatingMicService.start(this)
        }

        setContent {
            val config by app.prefs.config.collectAsState()
            ProvideUiLanguage(
                uiLanguage = config.uiLanguage,
                hostActivity = this@SettingsActivity
            ) {
                PixelVoiceTheme(themeMode = config.appThemeMode) {
                    PixelVoiceSettingsApp(app = app)
                }
            }
        }
    }
}

private data class SettingsNavItem(
    val index: Int,
    @StringRes val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PixelVoiceSettingsApp(app: PixelVoiceApp) {
    val config by app.prefs.config.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var companionTab by remember { mutableIntStateOf(2) }
    var userSideBySideOverride by remember { mutableStateOf<Boolean?>(null) }
    var showLangMenu by remember { mutableStateOf(false) }

    val navItems = remember {
        listOf(
            SettingsNavItem(0, R.string.nav_setup, Icons.Rounded.KeyboardVoice),
            SettingsNavItem(1, R.string.nav_languages, Icons.Rounded.Language),
            SettingsNavItem(2, R.string.nav_models, Icons.Rounded.AutoAwesome),
            SettingsNavItem(3, R.string.nav_backup, Icons.Rounded.Backup),
            SettingsNavItem(4, R.string.nav_about, Icons.Rounded.SystemUpdate)
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val canSideBySide = maxWidth >= 480.dp
        val defaultTwoPane = maxWidth >= 600.dp
        val isTwoPane = canSideBySide && (userSideBySideOverride ?: defaultTwoPane)
        val useRail = maxWidth >= 540.dp

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("DxVoice-IME", fontWeight = FontWeight.Bold)
                            Text(
                                text = stringResource(R.string.app_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showLangMenu = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.Translate,
                                    contentDescription = stringResource(R.string.ui_language_title)
                                )
                            }
                            DropdownMenu(
                                expanded = showLangMenu,
                                onDismissRequest = { showLangMenu = false }
                            ) {
                                listOf(
                                    AppUiLanguage.SYSTEM to R.string.ui_language_system,
                                    AppUiLanguage.ZH_TW to R.string.ui_language_zh,
                                    AppUiLanguage.ZH_CN to R.string.ui_language_zh_cn,
                                    AppUiLanguage.EN to R.string.ui_language_en,
                                    AppUiLanguage.JA to R.string.ui_language_ja
                                ).forEach { (lang, labelRes) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = stringResource(labelRes) + if (config.uiLanguage == lang) " ✓" else "",
                                                fontWeight = if (config.uiLanguage == lang) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            app.prefs.update { it.copy(uiLanguage = lang) }
                                            showLangMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        if (canSideBySide) {
                            TextButton(
                                onClick = {
                                    userSideBySideOverride = !isTwoPane
                                }
                            ) {
                                Icon(
                                    imageVector = if (isTwoPane) Icons.Rounded.ViewAgenda else Icons.Rounded.VerticalSplit,
                                    contentDescription = null
                                )
                                Text(
                                    if (isTwoPane) {
                                        stringResource(R.string.layout_single_pane)
                                    } else {
                                        stringResource(R.string.layout_side_by_side)
                                    }
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        navItems.forEach { item ->
                            NavigationBarItem(
                                selected = selectedTab == item.index,
                                onClick = { selectedTab = item.index },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = {
                                    Text(
                                        text = stringResource(item.labelRes),
                                        maxLines = 2,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail {
                        navItems.forEach { item ->
                            NavigationRailItem(
                                selected = selectedTab == item.index,
                                onClick = {
                                    selectedTab = item.index
                                    if (companionTab == item.index) {
                                        companionTab = (item.index + 1) % navItems.size
                                    }
                                },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = {
                                    Text(
                                        text = stringResource(item.labelRes),
                                        maxLines = 2,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            )
                        }
                    }
                }

                if (isTwoPane) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            RenderSettingsTab(tabIndex = selectedTab, app = app)
                        }
                        VerticalDivider()
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                navItems.filter { it.index != selectedTab }.forEach { item ->
                                    FilterChip(
                                        selected = companionTab == item.index,
                                        onClick = { companionTab = item.index },
                                        label = { Text(stringResource(item.labelRes)) }
                                    )
                                }
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                RenderSettingsTab(tabIndex = companionTab, app = app)
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RenderSettingsTab(tabIndex = selectedTab, app = app)
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderSettingsTab(tabIndex: Int, app: PixelVoiceApp) {
    when (tabIndex) {
        0 -> SetupAndBehaviorTab(app = app)
        1 -> OfflineLanguagesScreen(modelManager = app.modelManager, prefs = app.prefs)
        2 -> GeminiSettingsScreen(
            apiKeyStore = app.apiKeyStore,
            polisher = app.geminiPolisher,
            prefs = app.prefs
        )
        3 -> BackupAndAppearanceScreen(app = app)
        4 -> UpdateSettingsScreen(prefs = app.prefs, updater = app.updater)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupAndBehaviorTab(app: PixelVoiceApp) {
    val context = LocalContext.current
    val activityContext = LocalHostActivity.current ?: context
    val lifecycleOwner = LocalLifecycleOwner.current
    val config by app.prefs.config.collectAsState()
    val accessState by app.entitlementManager.accessState.collectAsState()

    var hasMicPermission by remember { mutableStateOf(checkMicPermission(context)) }
    var isImeEnabled by remember { mutableStateOf(checkImeEnabled(context)) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var accessibilityActive by remember { mutableStateOf(PixelVoiceAccessibilityService.isRunning()) }
    var accessibilityEnabledInSettings by remember {
        mutableStateOf(PixelVoiceAccessibilityService.isEnabledInSystemSettings(context))
    }
    var sandboxText by remember { mutableStateOf("") }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    var upgradeDialogFeature by remember { mutableStateOf<ProFeature?>(null) }
    var showUpgradeDialog by remember { mutableStateOf(false) }

    val openAccessibilitySettingsDirect: () -> Unit = {
        val serviceComponent = ComponentName(
            context,
            PixelVoiceAccessibilityService::class.java
        ).flattenToString()
        val args = Bundle().apply {
            putString(":settings:fragment_args_key", serviceComponent)
        }
        val directIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", serviceComponent)
            putExtra(":settings:show_fragment_args", args)
        }
        runCatching { activityContext.startActivity(directIntent) }
            .onFailure { activityContext.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    val openAccessibilitySettings: () -> Unit = {
        showAccessibilityDisclosure = true
    }

    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            title = { Text(stringResource(R.string.a11y_disclosure_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = stringResource(R.string.a11y_disclosure_body),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAccessibilityDisclosure = false
                        openAccessibilitySettingsDirect()
                    }
                ) {
                    Text(stringResource(R.string.a11y_disclosure_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDisclosure = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showUpgradeDialog) {
        ProUpgradeDialog(
            entitlementManager = app.entitlementManager,
            highlightedFeature = upgradeDialogFeature,
            onDismiss = {
                showUpgradeDialog = false
                upgradeDialogFeature = null
            }
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMicPermission = checkMicPermission(context)
                isImeEnabled = checkImeEnabled(context)
                canOverlay = Settings.canDrawOverlays(context)
                accessibilityActive = PixelVoiceAccessibilityService.isRunning()
                accessibilityEnabledInSettings = PixelVoiceAccessibilityService.isEnabledInSystemSettings(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Pro / Trial Status Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val titleText = when (val s = accessState) {
                        is ProAccessState.BetaFree -> stringResource(R.string.pro_status_beta)
                        is ProAccessState.ProPurchased -> stringResource(R.string.pro_status_purchased)
                        is ProAccessState.TrialActive -> stringResource(R.string.pro_status_trial, s.remainingHours)
                        is ProAccessState.FreeLocked -> stringResource(R.string.pro_status_free)
                    }
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = stringResource(R.string.pro_card_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                    )
                }
                OutlinedButton(
                    onClick = {
                        upgradeDialogFeature = null
                        showUpgradeDialog = true
                    }
                ) {
                    Text(
                        if (accessState is ProAccessState.ProPurchased) {
                            stringResource(R.string.pro_btn_plan)
                        } else {
                            stringResource(R.string.pro_btn_view)
                        }
                    )
                }
            }
        }

        // Quick Setup Checklist
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.setup_quick_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            activityContext.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        }
                    ) {
                        if (isImeEnabled) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        }
                        Text(
                            if (isImeEnabled) {
                                stringResource(R.string.setup_ime_enabled)
                            } else {
                                stringResource(R.string.setup_enable_ime)
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val imm = context.getSystemService(InputMethodManager::class.java)
                            imm?.showInputMethodPicker()
                        }
                    ) {
                        Text(stringResource(R.string.setup_switch_ime))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    ) {
                        Text(
                            if (hasMicPermission) {
                                stringResource(R.string.setup_mic_granted)
                            } else {
                                stringResource(R.string.setup_mic_grant)
                            }
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        OutlinedButton(
                            onClick = {
                                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        ) {
                            Text(stringResource(R.string.setup_allow_notif))
                        }
                    }
                }
            }
        }

        // Core Behavior Toggles
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.behavior_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                SettingSwitchRow(
                    title = stringResource(R.string.behavior_auto_listen_title),
                    subtitle = stringResource(R.string.behavior_auto_listen_sub),
                    checked = config.autoListenOnStart,
                    onCheckedChange = { v -> app.prefs.update { it.copy(autoListenOnStart = v) } }
                )
                SettingSwitchRow(
                    title = stringResource(R.string.behavior_auto_return_title),
                    subtitle = stringResource(R.string.behavior_auto_return_sub),
                    checked = config.autoReturnToPreviousIme && app.entitlementManager.hasProAccess(),
                    onCheckedChange = { v ->
                        if (v && !app.entitlementManager.hasProAccess()) {
                            upgradeDialogFeature = ProFeature.AUTO_RETURN_IME
                            showUpgradeDialog = true
                        } else {
                            app.prefs.update { it.copy(autoReturnToPreviousIme = v) }
                        }
                    }
                )

                Text(
                    text = stringResource(R.string.behavior_delay_label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0L to R.string.delay_0,
                        450L to R.string.delay_450,
                        1000L to R.string.delay_1000,
                        1800L to R.string.delay_1800,
                        2500L to R.string.delay_2500
                    ).forEach { (delayMs, labelRes) ->
                        FilterChip(
                            selected = config.autoReturnToPreviousIme && config.autoReturnDelayMs == delayMs,
                            onClick = {
                                app.prefs.update {
                                    it.copy(autoReturnToPreviousIme = true, autoReturnDelayMs = delayMs)
                                }
                            },
                            label = { Text(stringResource(labelRes)) }
                        )
                    }
                    FilterChip(
                        selected = !config.autoReturnToPreviousIme,
                        onClick = { app.prefs.update { it.copy(autoReturnToPreviousIme = false) } },
                        label = { Text(stringResource(R.string.delay_manual)) }
                    )
                }

                Text(
                    text = stringResource(R.string.behavior_silence_label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        2500L to R.string.silence_2500,
                        2700L to R.string.silence_2700,
                        3000L to R.string.silence_3000,
                        4000L to R.string.silence_4000
                    ).forEach { (ms, labelRes) ->
                        FilterChip(
                            selected = config.silenceTimeoutMs == ms,
                            onClick = { app.prefs.update { it.copy(silenceTimeoutMs = ms) } },
                            label = { Text(stringResource(labelRes)) }
                        )
                    }
                }
            }
        }

        // Floating Ball Shortcut Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.floating_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.floating_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!canOverlay) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            activityContext.startActivity(intent)
                        }
                    ) {
                        Text(stringResource(R.string.floating_grant_overlay))
                    }
                }

                Text(
                    text = when {
                        accessibilityActive -> stringResource(R.string.floating_a11y_active)
                        accessibilityEnabledInSettings -> stringResource(R.string.floating_a11y_disconnected)
                        else -> stringResource(R.string.floating_a11y_disabled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (accessibilityActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                if (!accessibilityActive) {
                    Button(
                        onClick = openAccessibilitySettings
                    ) {
                        Text(
                            if (accessibilityEnabledInSettings) {
                                stringResource(R.string.floating_a11y_btn_restart)
                            } else {
                                stringResource(R.string.floating_a11y_btn_enable)
                            }
                        )
                    }
                }

                SettingSwitchRow(
                    title = stringResource(R.string.floating_enable_title),
                    subtitle = if (canOverlay) {
                        stringResource(R.string.floating_enable_sub_ok)
                    } else {
                        stringResource(R.string.floating_enable_sub_need_perm)
                    },
                    checked = config.floatingBallEnabled && app.entitlementManager.hasProAccess(),
                    onCheckedChange = { enabled ->
                        if (enabled && !app.entitlementManager.hasProAccess()) {
                            upgradeDialogFeature = ProFeature.FLOATING_BALL
                            showUpgradeDialog = true
                        } else if (enabled && !Settings.canDrawOverlays(context)) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            activityContext.startActivity(intent)
                        } else {
                            app.prefs.update { it.copy(floatingBallEnabled = enabled) }
                            if (enabled) {
                                FloatingMicService.start(context)
                                if (!PixelVoiceAccessibilityService.isRunning()) {
                                    openAccessibilitySettings()
                                }
                            } else {
                                FloatingMicService.stop(context)
                            }
                        }
                    }
                )

                Text(
                    text = stringResource(R.string.floating_alpha, (config.floatingBallAlpha * 100).roundToInt()),
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = config.floatingBallAlpha,
                    onValueChange = { alpha ->
                        app.prefs.update { it.copy(floatingBallAlpha = alpha.coerceIn(0.25f, 1.0f)) }
                    },
                    valueRange = 0.25f..1.0f
                )

                Text(
                    text = stringResource(
                        R.string.floating_coords,
                        config.floatingBallOuterX,
                        config.floatingBallOuterY,
                        config.floatingBallInnerX,
                        config.floatingBallInnerY
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        app.prefs.update {
                            it.copy(
                                floatingBallOuterX = 40,
                                floatingBallOuterY = 420,
                                floatingBallInnerX = 60,
                                floatingBallInnerY = 520
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.floating_reset_pos))
                }
            }
        }

        // Sandbox test input box
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.sandbox_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.sandbox_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = sandboxText,
                    onValueChange = { sandboxText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    placeholder = { Text(stringResource(R.string.sandbox_placeholder)) },
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

private fun checkMicPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
}

private fun checkImeEnabled(context: android.content.Context): Boolean {
    val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

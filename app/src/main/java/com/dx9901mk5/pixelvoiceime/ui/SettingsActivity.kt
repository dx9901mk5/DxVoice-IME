package com.dx9901mk5.pixelvoiceime.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dx9901mk5.pixelvoiceime.PixelVoiceApp
import com.dx9901mk5.pixelvoiceime.ime.FloatingMicService
import com.dx9901mk5.pixelvoiceime.ui.theme.PixelVoiceTheme

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
            PixelVoiceTheme(themeMode = config.appThemeMode) {
                PixelVoiceSettingsApp(app = app)
            }
        }
    }
}

private data class SettingsNavItem(
    val index: Int,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PixelVoiceSettingsApp(app: PixelVoiceApp) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var companionTab by remember { mutableIntStateOf(3) }

    val navItems = remember {
        listOf(
            SettingsNavItem(0, "啟用行為", Icons.Rounded.KeyboardVoice),
            SettingsNavItem(1, "離線語系", Icons.Rounded.Language),
            SettingsNavItem(2, "AI 與端點", Icons.Rounded.AutoAwesome),
            SettingsNavItem(3, "外觀備份", Icons.Rounded.Backup),
            SettingsNavItem(4, "檢查更新", Icons.Rounded.SystemUpdate)
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isTwoPane = maxWidth >= 680.dp
        val useRail = maxWidth >= 540.dp

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("PixelVoice IME", fontWeight = FontWeight.Bold)
                            Text(
                                text = "本地極速 / Gemini / 自架端點語音輸入法",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                                label = { Text(item.label) }
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
                                label = { Text(item.label) }
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
                                        label = { Text(item.label) }
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val config by app.prefs.config.collectAsState()

    var hasMicPermission by remember { mutableStateOf(checkMicPermission(context)) }
    var isImeEnabled by remember { mutableStateOf(checkImeEnabled(context)) }
    var sandboxText by remember { mutableStateOf("") }

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
        // Quick Setup Checklist
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "快速啟用步驟",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        }
                    ) {
                        if (isImeEnabled) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        }
                        Text(if (isImeEnabled) " 已啟用輸入法" else "1. 啟用 PixelVoice 輸入法")
                    }
                    OutlinedButton(
                        onClick = {
                            val imm = context.getSystemService(InputMethodManager::class.java)
                            imm?.showInputMethodPicker()
                        }
                    ) {
                        Text("2. 切換輸入法")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    ) {
                        Text(if (hasMicPermission) "麥克風已授權 ✓" else "3. 授予麥克風權限")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        OutlinedButton(
                            onClick = {
                                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        ) {
                            Text("允許更新通知")
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
                    text = "自動收音與切回原輸入法行為",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                SettingSwitchRow(
                    title = "切換輸入法時自主啟動收音",
                    subtitle = "一旦切到 PixelVoice IME 立即開始語音接收與判定（密碼框自動略過）",
                    checked = config.autoListenOnStart,
                    onCheckedChange = { v -> app.prefs.update { it.copy(autoListenOnStart = v) } }
                )
                SettingSwitchRow(
                    title = "結束後立刻切回原輸入法",
                    subtitle = "記憶先前鍵盤，修正貼回完成後自動切回原本使用的輸入法",
                    checked = config.autoReturnToPreviousIme,
                    onCheckedChange = { v -> app.prefs.update { it.copy(autoReturnToPreviousIme = v) } }
                )

                Text(
                    text = "切回原輸入法延遲時間",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0L to "立刻 (0秒)",
                        450L to "極快 (0.45秒)",
                        1000L to "預覽 (1秒)",
                        1800L to "寬裕 (1.8秒)",
                        2500L to "寬裕 (2.5秒)"
                    ).forEach { (delayMs, label) ->
                        FilterChip(
                            selected = config.autoReturnToPreviousIme && config.autoReturnDelayMs == delayMs,
                            onClick = {
                                app.prefs.update {
                                    it.copy(autoReturnToPreviousIme = true, autoReturnDelayMs = delayMs)
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                    FilterChip(
                        selected = !config.autoReturnToPreviousIme,
                        onClick = { app.prefs.update { it.copy(autoReturnToPreviousIme = false) } },
                        label = { Text("手動點擊切回") }
                    )
                }

                Text(
                    text = "說話停頓自動結束時間 (2秒內停頓續講，停滿此秒數才結束收話)",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        2500L to "2.5秒",
                        2700L to "2.7秒",
                        3000L to "3.0秒 (預設)",
                        4000L to "4.0秒"
                    ).forEach { (ms, label) ->
                        FilterChip(
                            selected = config.silenceTimeoutMs == ms,
                            onClick = { app.prefs.update { it.copy(silenceTimeoutMs = ms) } },
                            label = { Text(label) }
                        )
                    }
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
                    text = "即時語音輸入測試框",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "點擊下方輸入框並切換成「PixelVoice 語音輸入法」，即可立即體驗自動收音 → Gemini 修正標點 → 自動貼回並切回原輸入法！",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = sandboxText,
                    onValueChange = { sandboxText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    placeholder = { Text("點此開始測試語音輸入…") },
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

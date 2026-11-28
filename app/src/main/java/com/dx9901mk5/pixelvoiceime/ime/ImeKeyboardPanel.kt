package com.dx9901mk5.pixelvoiceime.ime

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dx9901mk5.pixelvoiceime.R
import com.dx9901mk5.pixelvoiceime.ai.LocalTranslator
import com.dx9901mk5.pixelvoiceime.asr.VoiceSessionState
import com.dx9901mk5.pixelvoiceime.data.AppThemeMode
import com.dx9901mk5.pixelvoiceime.models.VoiceLanguageItem
import com.dx9901mk5.pixelvoiceime.ui.theme.PixelVoiceTheme

@Composable
fun ImeKeyboardPanel(
    state: VoiceSessionState,
    activeLanguage: VoiceLanguageItem,
    isOfflinePackReady: Boolean,
    isGeminiReady: Boolean,
    targetTranslationCode: String,
    themeMode: AppThemeMode = AppThemeMode.DYNAMIC_PIXEL,
    previousImeLabel: String,
    bottomLiftDp: Int = 16,
    systemNavBarDp: Int = 0,
    onCycleLanguage: () -> Unit,
    onCycleTranslation: () -> Unit,
    onCycleBottomLift: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenOfflineLanguages: () -> Unit = onOpenSettings,
    onMicPrimaryAction: () -> Unit,
    onSwitchToPreviousIme: () -> Unit,
    onUndoAiPolish: () -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onOpenPermissionSetup: () -> Unit
) {
    PixelVoiceTheme(themeMode = themeMode) {
        val composeNavBarDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val safeNavBarDp = maxOf(composeNavBarDp, systemNavBarDp.coerceAtLeast(0).dp)
        val animatedLiftDp by animateDpAsState(
            targetValue = bottomLiftDp.coerceIn(0, 80).dp,
            animationSpec = tween(durationMillis = 180),
            label = "imeBottomLift"
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 680.dp)
                        .padding(
                            start = 14.dp,
                            end = 14.dp,
                            top = 10.dp,
                            bottom = 12.dp + safeNavBarDp + animatedLiftDp
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(50))
                                .clickable(onClick = onCycleLanguage),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(50)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Language,
                                    contentDescription = stringResource(R.string.ime_switch_lang),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = activeLanguage.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isOfflinePackReady) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.ime_offline_ready),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        if (!isOfflinePackReady) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable(onClick = onOpenOfflineLanguages),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(50)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        text = stringResource(R.string.ime_online_download_pack),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val transOption = LocalTranslator.findTarget(targetTranslationCode)
                            val transActive = targetTranslationCode.isNotBlank()
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable(onClick = onCycleTranslation),
                                color = if (transActive) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = stringResource(R.string.ime_trans_prefix, transOption.shortLabel),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (transActive) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            if (isGeminiReady) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "Gemini",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                            IconButton(
                                onClick = onCycleBottomLift,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VerticalAlignTop,
                                    contentDescription = stringResource(R.string.ime_lift_cd),
                                    modifier = Modifier.size(20.dp),
                                    tint = if (bottomLiftDp > 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            IconButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = stringResource(R.string.ime_settings_cd),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(74.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when (state) {
                            is VoiceSessionState.Listening -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    VoiceWaveformBars(amplitude = state.amplitude)
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = state.partialText.ifBlank {
                                            if (state.speechDetected) {
                                                stringResource(R.string.ime_listening_detected)
                                            } else if (!isOfflinePackReady) {
                                                stringResource(R.string.ime_listening_prompt_online)
                                            } else {
                                                stringResource(R.string.ime_listening_prompt)
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            is VoiceSessionState.Polishing -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.5.dp
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = state.statusText,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        if (state.rawText.isNotBlank()) {
                                            Text(
                                                text = state.rawText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            is VoiceSessionState.Committed -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = if (state.returningSoon) {
                                                    stringResource(R.string.ime_committed_returning)
                                                } else {
                                                    stringResource(R.string.ime_committed_done)
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = state.finalText,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (state.wasAiPolished && state.rawText.isNotBlank() && state.rawText != state.finalText) {
                                        TextButton(onClick = onUndoAiPolish) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Rounded.Undo,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(stringResource(R.string.ime_undo_raw), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }

                            is VoiceSessionState.PermissionRequired -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.ime_need_mic),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    FilledTonalButton(onClick = onOpenPermissionSetup) {
                                        Text(stringResource(R.string.ime_grant_now))
                                    }
                                }
                            }

                            is VoiceSessionState.PasswordProtected -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.ime_password_disabled),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            is VoiceSessionState.Error -> {
                                Text(
                                    text = stringResource(R.string.ime_error_retry, state.message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2
                                )
                            }

                            VoiceSessionState.Idle -> {
                                Text(
                                    text = stringResource(R.string.ime_idle_prompt),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onSwitchToPreviousIme,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .widthIn(max = 112.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Keyboard,
                                contentDescription = stringResource(R.string.ime_return_cd),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = previousImeLabel.ifBlank { stringResource(R.string.ime_return_default) },
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        val isListening = state is VoiceSessionState.Listening
                        val orbColor = if (isListening) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                        Surface(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(84.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onMicPrimaryAction),
                            color = orbColor,
                            shape = CircleShape,
                            shadowElevation = 6.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                    contentDescription = if (isListening) {
                                        stringResource(R.string.ime_stop_cd)
                                    } else {
                                        stringResource(R.string.ime_start_cd)
                                    },
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.align(Alignment.CenterEnd),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilledTonalIconButton(onClick = onBackspace) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Backspace,
                                    contentDescription = stringResource(R.string.ime_backspace_cd)
                                )
                            }
                            FilledTonalIconButton(onClick = onEnter) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardReturn,
                                    contentDescription = stringResource(R.string.ime_enter_cd)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceWaveformBars(amplitude: Float) {
    val multipliers = listOf(0.45f, 0.78f, 1.0f, 0.85f, 0.62f, 0.92f, 0.50f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(26.dp)
    ) {
        multipliers.forEach { factor ->
            val targetHeight = (6f + 20f * amplitude * factor).dp
            val animatedHeight by animateDpAsState(
                targetValue = targetHeight,
                animationSpec = tween(durationMillis = 90),
                label = "waveBar"
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(animatedHeight)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

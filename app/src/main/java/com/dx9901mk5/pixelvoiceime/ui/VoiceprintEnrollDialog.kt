package com.dx9901mk5.pixelvoiceime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dx9901mk5.pixelvoiceime.R
import com.dx9901mk5.pixelvoiceime.asr.VoiceprintManager
import kotlinx.coroutines.launch

@Composable
fun VoiceprintEnrollDialog(
    voiceprintManager: VoiceprintManager,
    onEnrolledComplete: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val enrolledCount by voiceprintManager.enrolledCount.collectAsState()
    var currentStep by remember { mutableIntStateOf(enrolledCount.coerceIn(0, VoiceprintManager.REQUIRED_SAMPLES - 1)) }
    var isRecording by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var amplitude by remember { mutableFloatStateOf(0f) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val prompts = listOf(
        stringResource(R.string.vp_prompt_1),
        stringResource(R.string.vp_prompt_2),
        stringResource(R.string.vp_prompt_3)
    )

    val doneMsg = stringResource(R.string.vp_enroll_all_done)
    val stepOkMsg = stringResource(R.string.vp_enroll_step_ok, currentStep + 1)

    AlertDialog(
        onDismissRequest = { if (!isRecording) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.vp_dialog_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.vp_dialog_guide),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = stringResource(
                        R.string.vp_step_indicator,
                        currentStep + 1,
                        VoiceprintManager.REQUIRED_SAMPLES,
                        enrolledCount
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.vp_read_aloud_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                        Text(
                            text = "「${prompts[currentStep]}」",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                if (isRecording) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.vp_recording_now, (amplitude * 100).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (!statusMsg.isNullOrBlank()) {
                    Text(
                        text = statusMsg!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !isRecording,
                        onClick = {
                            isRecording = true
                            progress = 0f
                            statusMsg = null
                            isError = false
                            val targetStep = currentStep
                            scope.launch {
                                val res = voiceprintManager.recordAndEnrollSample(
                                    sampleIndex = targetStep,
                                    onProgress = { p, amp ->
                                        progress = p
                                        amplitude = amp
                                    }
                                )
                                isRecording = false
                                res.fold(
                                    onSuccess = {
                                        if (voiceprintManager.isEnrolled()) {
                                            statusMsg = doneMsg
                                            isError = false
                                            onEnrolledComplete()
                                        } else {
                                            statusMsg = stepOkMsg
                                            isError = false
                                            currentStep = (targetStep + 1).coerceAtMost(VoiceprintManager.REQUIRED_SAMPLES - 1)
                                        }
                                    },
                                    onFailure = { e ->
                                        statusMsg = e.localizedMessage ?: "錄製失敗，請重試"
                                        isError = true
                                    }
                                )
                            }
                        }
                    ) {
                        Text(
                            if (isRecording) {
                                stringResource(R.string.vp_btn_recording)
                            } else {
                                stringResource(R.string.vp_btn_record_step, currentStep + 1)
                            }
                        )
                    }
                    if (enrolledCount > 0 && !isRecording) {
                        OutlinedButton(
                            onClick = {
                                voiceprintManager.clearEnrollment()
                                currentStep = 0
                                statusMsg = null
                                isError = false
                            }
                        ) {
                            Text(stringResource(R.string.vp_btn_reset))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isRecording,
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

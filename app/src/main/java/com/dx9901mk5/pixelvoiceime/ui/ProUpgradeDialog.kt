package com.dx9901mk5.pixelvoiceime.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dx9901mk5.pixelvoiceime.billing.ProAccessState
import com.dx9901mk5.pixelvoiceime.billing.ProEntitlementManager
import com.dx9901mk5.pixelvoiceime.billing.ProFeature

@Composable
fun ProUpgradeDialog(
    entitlementManager: ProEntitlementManager,
    highlightedFeature: ProFeature? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val accessState by entitlementManager.accessState.collectAsState()
    val planOptions by entitlementManager.planOptions.collectAsState()
    val billingMessage by entitlementManager.billingMessage.collectAsState()
    var promoCodeInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "DxVoice-IME Pro 進階授權",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                val statusSubtitle = when (val s = accessState) {
                    is ProAccessState.BetaFree -> "測試期間全功能免費開放（正式版提供 3 天免費試用）"
                    is ProAccessState.ProPurchased -> "您已解鎖 Pro 永久／訂閱授權 ✓"
                    is ProAccessState.TrialActive -> "3 天免費試用中（剩餘約 ${s.remainingHours} 小時）"
                    is ProAccessState.FreeLocked -> "3 天免費試用已結束，升級 Pro 即可繼續使用以下 5 大功能"
                }
                Text(
                    text = statusSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (highlightedFeature != null) {
                    Text(
                        text = "「${highlightedFeature.displayName}」屬於 Pro 進階功能。",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "免費版永久提供：核心語音輸入、離線語音辨識、自動標點、台灣繁中轉換與手動切回鍵盤。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Pro 專屬 5 大效率功能：",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        ProFeature.entries.forEach { feature ->
                            Text(
                                text = "• ${feature.displayName}：${feature.summary}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                planOptions.forEach { plan ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (plan.badge != null) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = plan.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (plan.badge != null) {
                                    Text(
                                        text = plan.badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                text = plan.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = activity != null && accessState !is ProAccessState.ProPurchased,
                                onClick = {
                                    if (activity != null) {
                                        entitlementManager.launchPurchaseFlow(activity, plan.productId)
                                    }
                                }
                            ) {
                                Text(
                                    if (accessState is ProAccessState.ProPurchased) {
                                        "已解鎖"
                                    } else {
                                        "選擇方案 · ${plan.fallbackPriceLabel}"
                                    }
                                )
                            }
                        }
                    }
                }

                // Friends & Family / Google Play Promo Code Redemption
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "親友兌換碼 / Google Play 促銷代碼",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = promoCodeInput,
                                onValueChange = { promoCodeInput = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text("輸入兌換碼") },
                                shape = RoundedCornerShape(12.dp)
                            )
                            Button(
                                enabled = promoCodeInput.isNotBlank(),
                                onClick = {
                                    if (entitlementManager.redeemPromoCode(activity, promoCodeInput)) {
                                        promoCodeInput = ""
                                    }
                                }
                            ) {
                                Text("兌換")
                            }
                        }
                    }
                }

                if (!billingMessage.isNullOrBlank()) {
                    Text(
                        text = billingMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { entitlementManager.restorePurchases() }
                ) {
                    Text("恢復 Google Play 已購買項目")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("關閉")
            }
        }
    )
}

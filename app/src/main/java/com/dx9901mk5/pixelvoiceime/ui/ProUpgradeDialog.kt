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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dx9901mk5.pixelvoiceime.R
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
    val activity = LocalHostActivity.current ?: (context as? Activity)
    val accessState by entitlementManager.accessState.collectAsState()
    val planOptions by entitlementManager.planOptions.collectAsState()
    val billingMessage by entitlementManager.billingMessage.collectAsState()
    var promoCodeInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.pro_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                val statusSubtitle = when (val s = accessState) {
                    is ProAccessState.BetaFree -> stringResource(R.string.pro_dialog_sub_beta)
                    is ProAccessState.ProPurchased -> stringResource(R.string.pro_dialog_sub_purchased)
                    is ProAccessState.TrialActive -> stringResource(R.string.pro_dialog_sub_trial, s.remainingHours)
                    is ProAccessState.FreeLocked -> stringResource(R.string.pro_dialog_sub_free)
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
                        text = stringResource(
                            R.string.pro_dialog_feature_highlight,
                            stringResource(highlightedFeature.displayNameRes)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = stringResource(R.string.pro_dialog_free_desc),
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
                            text = stringResource(R.string.pro_dialog_features_header),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        ProFeature.entries.forEach { feature ->
                            Text(
                                text = "• ${stringResource(feature.displayNameRes)}：${stringResource(feature.summaryRes)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                planOptions.forEach { plan ->
                    val isLifetime = plan.productId == ProEntitlementManager.SKU_PRO_LIFETIME
                    val planTitle = if (isLifetime) stringResource(R.string.pro_plan_lifetime_title) else plan.title
                    val planSubtitle = if (isLifetime) stringResource(R.string.pro_plan_lifetime_sub) else plan.subtitle
                    val planBadge = if (isLifetime) stringResource(R.string.pro_plan_lifetime_badge) else plan.badge
                    val planPrice = plan.productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
                        ?: if (isLifetime) stringResource(R.string.pro_plan_lifetime_price) else plan.fallbackPriceLabel

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (planBadge != null) {
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
                                    text = planTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (planBadge != null) {
                                    Text(
                                        text = planBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                text = planSubtitle,
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
                                        stringResource(R.string.pro_dialog_unlocked)
                                    } else {
                                        stringResource(R.string.pro_dialog_select_plan, planPrice)
                                    }
                                )
                            }
                        }
                    }
                }

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
                            text = stringResource(R.string.pro_promo_title),
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
                                label = { Text(stringResource(R.string.pro_promo_label)) },
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
                                Text(stringResource(R.string.pro_promo_redeem))
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
                    Text(stringResource(R.string.pro_restore_btn))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

package com.dx9901mk5.pixelvoiceime.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.dx9901mk5.pixelvoiceime.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

enum class ProFeature(val displayName: String, val summary: String) {
    FLOATING_BALL("懸浮語音捷徑球", "跟隨其他鍵盤自動顯示、點擊直切本輸入法與摺疊機雙螢幕記憶"),
    AUTO_RETURN_IME("自動切回原本輸入法", "語音說完貼回文字後自動切回原先使用的鍵盤"),
    LOCAL_LLM("本地極速小模型修潤", "離線使用 Qwen2.5 0.5B 本地 AI 進行台灣口語與錯字修潤"),
    CUSTOM_DICTIONARY("自定義詞庫與慣用語學習", "專有名詞、0ms 即時詞彙替換與個人常用詞自動學習"),
    CLOUD_BACKUP("雲端與資料夾備份還原", "將所有設定、懸浮球座標與自定義詞庫一鍵備份至 Google 雲端硬碟或本地資料夾")
}

sealed interface ProAccessState {
    data object BetaFree : ProAccessState
    data object ProPurchased : ProAccessState
    data class TrialActive(val remainingHours: Long, val remainingDaysRounded: Int) : ProAccessState
    data object FreeLocked : ProAccessState
}

data class ProPlanOption(
    val productId: String,
    val title: String,
    val subtitle: String,
    val fallbackPriceLabel: String,
    val isSubscription: Boolean,
    val badge: String? = null,
    val productDetails: ProductDetails? = null
)

/**
 * Manages Freemium / Pro entitlements, 3-day free trial (for production releases),
 * beta-free testing override, and Google Play Billing v7 in-app purchases & subscriptions.
 */
class ProEntitlementManager(context: Context) : PurchasesUpdatedListener {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _accessState = MutableStateFlow(computeAccessState())
    val accessState: StateFlow<ProAccessState> = _accessState.asStateFlow()

    private val _billingMessage = MutableStateFlow<String?>(null)
    val billingMessage: StateFlow<String?> = _billingMessage.asStateFlow()

    private val _planOptions = MutableStateFlow(defaultPlanOptions())
    val planOptions: StateFlow<List<ProPlanOption>> = _planOptions.asStateFlow()

    private var billingClient: BillingClient? = null

    init {
        ensureTrialStartRecorded()
        refreshAccessState()
        initBillingClient()
    }

    fun hasProAccess(): Boolean {
        return when (computeAccessState().also { _accessState.value = it }) {
            is ProAccessState.BetaFree,
            is ProAccessState.ProPurchased,
            is ProAccessState.TrialActive -> true
            is ProAccessState.FreeLocked -> false
        }
    }

    fun refreshAccessState() {
        _accessState.value = computeAccessState()
    }

    fun clearMessage() {
        _billingMessage.value = null
    }

    private fun ensureTrialStartRecorded(): Long {
        val existing = prefs.getLong(KEY_FIRST_INSTALL_MS, 0L)
        if (existing > 0L) return existing
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_FIRST_INSTALL_MS, now).apply()
        return now
    }

    private fun computeAccessState(): ProAccessState {
        if (prefs.getBoolean(KEY_PRO_PURCHASED, false)) {
            return ProAccessState.ProPurchased
        }
        if (BuildConfig.IS_BETA_FREE) {
            return ProAccessState.BetaFree
        }
        val firstInstall = ensureTrialStartRecorded()
        val elapsedMs = (System.currentTimeMillis() - firstInstall).coerceAtLeast(0L)
        val remainingMs = TRIAL_DURATION_MS - elapsedMs
        return if (remainingMs > 0L) {
            val hours = (remainingMs / (60L * 60L * 1000L)).coerceAtLeast(1L)
            val days = ((hours + 23L) / 24L).toInt().coerceAtLeast(1)
            ProAccessState.TrialActive(remainingHours = hours, remainingDaysRounded = days)
        } else {
            ProAccessState.FreeLocked
        }
    }

    private fun initBillingClient() {
        runCatching {
            val client = BillingClient.newBuilder(appContext)
                .setListener(this)
                .enablePendingPurchases()
                .build()
            billingClient = client
            connectAndQuery(client)
        }
    }

    private fun connectAndQuery(client: BillingClient) {
        if (client.isReady) {
            queryActivePurchases(client)
            queryAvailableProducts(client)
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryActivePurchases(client)
                    queryAvailableProducts(client)
                }
            }

            override fun onBillingServiceDisconnected() {
                // Will reconnect automatically on next user action
            }
        })
    }

    fun restorePurchases() {
        val client = billingClient
        if (client == null) {
            _billingMessage.value = "Google Play 帳單服務尚未就緒"
            return
        }
        _billingMessage.value = "正在檢查 Google Play 購買紀錄…"
        connectAndQuery(client)
    }

    /**
     * Redeems either a built-in friends & family code (verified via SHA-256 hash)
     * or opens Google Play official promo code redemption page.
     */
    fun redeemPromoCode(activity: Activity?, rawCode: String): Boolean {
        val normalized = rawCode.trim().uppercase()
        if (normalized.isBlank()) {
            _billingMessage.value = "請輸入親友兌換碼或 Google Play 促銷代碼"
            return false
        }
        val hash = sha256Hex("dxvoice_vip_salt::$normalized")
        if (hash in FRIENDS_PROMO_HASHES) {
            prefs.edit()
                .putBoolean(KEY_PRO_PURCHASED, true)
                .putString(KEY_REDEEMED_CODE_HASH, hash)
                .apply()
            refreshAccessState()
            _billingMessage.value = "親友兌換碼驗證成功！已永久解鎖 DxVoice-IME Pro 全功能 ✓"
            return true
        }
        if (activity != null) {
            val redeemUrl = "https://play.google.com/redeem?code=${Uri.encode(normalized)}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(redeemUrl))
            runCatching { activity.startActivity(intent) }
                .onSuccess {
                    _billingMessage.value = "已開啟 Google Play 促銷代碼兌換頁，兌換完成後請點「恢復已購買項目」"
                }
                .onFailure {
                    _billingMessage.value = "兌換碼無效，請確認輸入是否正確"
                }
        } else {
            _billingMessage.value = "兌換碼無效，請確認輸入是否正確"
        }
        return false
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun launchPurchaseFlow(activity: Activity, productId: String) {
        if (BuildConfig.IS_BETA_FREE) {
            _billingMessage.value = "目前為測試期間，全功能已免費開放使用（正式版上架後開放內購結帳）。"
            return
        }
        val client = billingClient
        if (client == null || !client.isReady) {
            _billingMessage.value = "無法連線至 Google Play 商店，請確認已登入 Google 帳號並重試。"
            client?.let { connectAndQuery(it) }
            return
        }
        val option = _planOptions.value.firstOrNull { it.productId == productId }
        val details = option?.productDetails
        if (details == null) {
            _billingMessage.value = "尚未從 Google Play 取得商品資訊，請稍後再試。"
            queryAvailableProducts(client)
            return
        }

        val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)

        if (option.isSubscription) {
            val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (!offerToken.isNullOrBlank()) {
                productParamsBuilder.setOfferToken(offerToken)
            }
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParamsBuilder.build()))
            .build()

        val result = client.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _billingMessage.value = "無法開啟結帳視窗（代碼 ${result.responseCode}）"
        }
    }

    private fun queryActivePurchases(client: BillingClient) {
        var foundActive = false

        val inAppParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(inAppParams) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        purchase.products.contains(SKU_PRO_LIFETIME)
                    ) {
                        foundActive = true
                        acknowledgeIfNeeded(client, purchase)
                    }
                }
            }
            val subParams = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            client.queryPurchasesAsync(subParams) { subResult, subPurchases ->
                if (subResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    subPurchases.forEach { purchase ->
                        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                            (purchase.products.contains(SKU_PRO_YEARLY) || purchase.products.contains(SKU_PRO_MONTHLY))
                        ) {
                            foundActive = true
                            acknowledgeIfNeeded(client, purchase)
                        }
                    }
                }
                if (foundActive) {
                    setProPurchased(true)
                    _billingMessage.value = "已驗證 DxVoice-IME Pro 授權 ✓"
                } else if (_billingMessage.value == "正在檢查 Google Play 購買紀錄…") {
                    _billingMessage.value = "未查到先前的 Pro 購買紀錄"
                }
            }
        }
    }

    private fun queryAvailableProducts(client: BillingClient) {
        val inAppList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SKU_PRO_LIFETIME)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(inAppList).build()
        ) { result, detailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && detailsList.isNotEmpty()) {
                mergeProductDetails(detailsList)
            }
        }

        val subsList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SKU_PRO_YEARLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SKU_PRO_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(subsList).build()
        ) { result, detailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && detailsList.isNotEmpty()) {
                mergeProductDetails(detailsList)
            }
        }
    }

    private fun mergeProductDetails(detailsList: List<ProductDetails>) {
        val byId = detailsList.associateBy { it.productId }
        _planOptions.value = _planOptions.value.map { option ->
            val details = byId[option.productId] ?: return@map option
            val formattedPrice = if (option.isSubscription) {
                details.subscriptionOfferDetails
                    ?.firstOrNull()
                    ?.pricingPhases
                    ?.pricingPhaseList
                    ?.lastOrNull()
                    ?.formattedPrice
            } else {
                details.oneTimePurchaseOfferDetails?.formattedPrice
            }
            option.copy(
                fallbackPriceLabel = formattedPrice ?: option.fallbackPriceLabel,
                productDetails = details
            )
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases.orEmpty().forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        billingClient?.let { acknowledgeIfNeeded(it, purchase) }
                        setProPurchased(true)
                        _billingMessage.value = "感謝支持！已成功解鎖 DxVoice-IME Pro 全功能 ✓"
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _billingMessage.value = "已取消購買"
            }
            else -> {
                _billingMessage.value = "購買未完成（代碼 ${result.responseCode}）"
            }
        }
    }

    private fun acknowledgeIfNeeded(client: BillingClient, purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { /* acknowledged */ }
    }

    private fun setProPurchased(purchased: Boolean) {
        prefs.edit().putBoolean(KEY_PRO_PURCHASED, purchased).apply()
        refreshAccessState()
    }

    private fun defaultPlanOptions(): List<ProPlanOption> = listOf(
        ProPlanOption(
            productId = SKU_PRO_LIFETIME,
            title = "DxVoice-IME Pro 終身買斷版",
            subtitle = "正式版含首次 3 天免費試用；一次買斷永久解鎖 5 大進階功能，無任何月費或年費",
            fallbackPriceLabel = "NT$300 終身買斷",
            isSubscription = false,
            badge = "一次買斷・無月費"
        )
    )

    companion object {
        private const val PREFS_NAME = "dxvoice_pro_entitlements"
        private const val KEY_FIRST_INSTALL_MS = "first_install_ms"
        private const val KEY_PRO_PURCHASED = "pro_purchased"
        private const val KEY_REDEEMED_CODE_HASH = "redeemed_code_hash"
        private const val TRIAL_DURATION_MS = 3L * 24L * 60L * 60L * 1000L // 3 days (72 hours)

        const val SKU_PRO_LIFETIME = "dxvoice_pro_lifetime"
        const val SKU_PRO_YEARLY = "dxvoice_pro_yearly"
        const val SKU_PRO_MONTHLY = "dxvoice_pro_monthly"

        // SHA-256 hashes of salted friends & family / tester VIP codes (plain codes stored offline in keystore_backup/)
        private val FRIENDS_PROMO_HASHES = setOf(
            "c133c7dfa8f32be492dd7a76fda9336a9b822522311e4b14c5a04db122d54334",
            "429ccf6de462cf72472f9ffe8a3cf5ad2b472d15d338d4e75d56fd17e5394be6",
            "a5393534586ccd6e51ae7def187b0e23ed10f2312f09ab963eb46452ac1ab2b7",
            "e46694f2892334ef5f391ab1d70ad1234e13b9f93d3c2596c73ae59d988e5476",
            "8afa5ae2589101ea627a0e609d31a29509894b44351924db842bca95763ce653"
        )
    }
}

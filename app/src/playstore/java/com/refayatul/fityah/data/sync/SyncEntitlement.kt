package com.refayatul.fityah.data.sync

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Google Play backed yearly premium access for the Play Store flavor. */
class SyncEntitlement(
    context: Context,
    private val rest: SupabaseRest,
    private val sessionProvider: suspend () -> SupabaseRest.Session?,
) : PurchasesUpdatedListener {
    private companion object {
        const val PRODUCT_ID = "fityah_sync"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _billing = MutableStateFlow(
        SyncBillingStatus(required = true, entitled = false, price = "$25", provider = "google_play"),
    )
    val billing: StateFlow<SyncBillingStatus> = _billing

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build(),
        )
        .build()

    @Volatile private var productDetails: ProductDetails? = null

    fun refresh() {
        scope.launch {
            try {
                refreshNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _billing.value = _billing.value.copy(error = e.message)
            }
        }
    }

    suspend fun refreshNow(): Boolean {
        val session = sessionProvider()
        if (session == null) {
            _billing.value = _billing.value.copy(entitled = false, validUntil = null, error = null)
            return false
        }
        connect()
        loadProduct()
        val purchases = queryPurchases()
        val active = purchases.firstOrNull {
            it.purchaseState == Purchase.PurchaseState.PURCHASED && PRODUCT_ID in it.products
        }
        if (active != null) {
            val result = rest.verifyPlayPurchase(session, active.purchaseToken)
            apply(result)
            return result.entitled
        }
        val result = rest.billingEntitlement(session)
        apply(result)
        return result.entitled
    }

    fun launchPurchase(activity: Activity) {
        scope.launch {
            try {
                val session = sessionProvider() ?: throw IllegalStateException("sign in first")
                connect()
                val details = loadProduct()
                val offer = yearlyOffer(details)
                    ?: throw IllegalStateException("The yearly plan is not available right now")
                val params = BillingFlowParams.newBuilder()
                    .setObfuscatedAccountId(accountHash(session.userId))
                    .setProductDetailsParamsList(
                        listOf(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(details)
                                .setOfferToken(offer.offerToken)
                                .build(),
                        ),
                    )
                    .build()
                val result = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params) }
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    throw IllegalStateException(result.debugMessage.ifBlank { "Google Play could not start checkout" })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _billing.value = _billing.value.copy(error = e.message)
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            scope.launch {
                try {
                    val session = sessionProvider() ?: return@launch
                    purchases
                        .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && PRODUCT_ID in it.products }
                        .forEach { apply(rest.verifyPlayPurchase(session, it.purchaseToken)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _billing.value = _billing.value.copy(error = e.message)
                }
            }
        } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            _billing.value = _billing.value.copy(error = result.debugMessage)
        }
    }

    private suspend fun connect() {
        if (client.isReady) return
        suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (!continuation.isActive) return
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(Unit)
                    else continuation.resumeWithException(
                        IllegalStateException(result.debugMessage.ifBlank { "Google Play billing is not available" }) as Throwable,
                    )
                }

                override fun onBillingServiceDisconnected() = Unit
            })
        }
    }

    private suspend fun loadProduct(): ProductDetails {
        productDetails?.let { return it }
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val details = suspendCancellableCoroutine { continuation ->
            client.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
            ) { result: BillingResult, queryResult: List<ProductDetails> ->
                if (!continuation.isActive) return@queryProductDetailsAsync
                val found = queryResult.firstOrNull()
                if (result.responseCode == BillingClient.BillingResponseCode.OK && found != null) {
                    continuation.resume(found)
                } else {
                    continuation.resumeWithException(
                        IllegalStateException(result.debugMessage.ifBlank { "The yearly plan is not available right now" }) as Throwable,
                    )
                }
            }
        }
        productDetails = details
        val price = yearlyOffer(details)
            ?.pricingPhases
            ?.pricingPhaseList
            ?.lastOrNull()
            ?.formattedPrice
        if (price != null) _billing.value = _billing.value.copy(price = price, error = null)
        return details
    }

    private fun yearlyOffer(details: ProductDetails) = details.subscriptionOfferDetails
        ?.firstOrNull { offer -> offer.pricingPhases.pricingPhaseList.lastOrNull()?.billingPeriod == "P1Y" }
        ?: details.subscriptionOfferDetails?.firstOrNull()

    private suspend fun queryPurchases(): List<Purchase> = suspendCancellableCoroutine { continuation ->
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
        ) { result: BillingResult, purchases: List<Purchase> ->
            if (!continuation.isActive) return@queryPurchasesAsync
            if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(purchases)
            else continuation.resumeWithException(
                IllegalStateException(result.debugMessage.ifBlank { "Could not check Google Play purchases" }) as Throwable,
            )
        }
    }

    private fun apply(result: SupabaseRest.BillingEntitlement) {
        _billing.value = SyncBillingStatus(
            required = true,
            entitled = result.entitled,
            price = _billing.value.price ?: result.price ?: "$25",
            provider = result.provider ?: "google_play",
            validUntil = result.validUntil,
        )
    }

    private fun accountHash(userId: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8)),
    )
}

package com.refayatul.fityah.data.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Polar-backed yearly premium access for the directly distributed full build. */
class SyncEntitlement(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val rest: SupabaseRest,
    private val sessionProvider: suspend () -> SupabaseRest.Session?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _billing = MutableStateFlow(
        SyncBillingStatus(required = true, entitled = false, price = "$25", provider = "polar"),
    )
    val billing: StateFlow<SyncBillingStatus> = _billing

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
        val result = rest.billingEntitlement(session)
        _billing.value = SyncBillingStatus(
            required = true,
            entitled = result.entitled,
            price = result.price ?: "$25",
            provider = result.provider ?: "polar",
            validUntil = result.validUntil,
        )
        return result.entitled
    }

    fun launchPurchase(activity: Activity) {
        scope.launch {
            try {
                val session = sessionProvider() ?: throw IllegalStateException("sign in first")
                val url = rest.polarBillingUrl(session)
                withContext(Dispatchers.Main) {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _billing.value = _billing.value.copy(error = e.message)
            }
        }
    }
}

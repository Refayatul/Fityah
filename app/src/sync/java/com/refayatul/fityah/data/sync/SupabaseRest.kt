package com.refayatul.fityah.data.sync

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.IOException
import java.time.Instant
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SupabaseRest(
    private val baseUrl: String = "https://pdixkzhncuuxuxwhdwdh.supabase.co",
    private val anonKey: String = ANON_KEY,
) {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    /** Thrown when the auth endpoint itself rejects a request, carrying the HTTP status. */
    class AuthHttpException(val code: Int, message: String) : IOException(message)

    /** Thrown when the Data API rejects a request, carrying the HTTP status. */
    class RestHttpException(val code: Int, message: String) : IOException(message)

    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val userId: String,
        val email: String?,
        val expiresAt: Long,
    )

    data class VaultRow(val saltB64: String, val paramsJson: String, val wrappedB64: String)

    data class SyncRow(
        val id: String,
        val namespace: String,
        val recordKey: String,
        val deviceId: String?,
        val ciphertext: String,
        val version: Long,
        val deleted: Boolean,
        val updatedAt: String,
    )

    data class DeviceRow(val id: String, val platform: String, val label: String, val lastSeen: String?)

    data class PullPosition(val updatedAt: String, val id: String)

    data class BillingEntitlement(
        val entitled: Boolean,
        val provider: String?,
        val validUntil: String?,
        val price: String?,
    )

    private fun parseSession(body: JsonObject, fallbackRefreshToken: String? = null): Session? {
        val token = body.get("access_token")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val user = body.get("user")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val refreshToken = body.get("refresh_token")
            ?.takeIf { !it.isJsonNull }
            ?.asString
            ?: fallbackRefreshToken
            ?: return null
        val expiresIn = body.get("expires_in")?.asLong?.coerceAtLeast(0L) ?: 3600L
        val expiresInMs = if (expiresIn > Long.MAX_VALUE / 1000L) Long.MAX_VALUE else expiresIn * 1000L
        val now = System.currentTimeMillis()
        return Session(
            accessToken = token,
            refreshToken = refreshToken,
            userId = user.get("id").asString,
            email = user.get("email")?.takeIf { !it.isJsonNull }?.asString,
            expiresAt = if (expiresInMs > Long.MAX_VALUE - now) Long.MAX_VALUE else now + expiresInMs,
        )
    }

    private fun postAuth(path: String, payload: JsonObject): JsonObject {
        val req = Request.Builder()
            .url("$baseUrl/auth/v1/$path")
            .header("apikey", anonKey)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val obj = if (text.isBlank()) {
                JsonObject()
            } else {
                try {
                    gson.fromJson(text, JsonObject::class.java)
                } catch (e: Exception) {
                    if (resp.isSuccessful) throw IOException("authentication returned an invalid response", e)
                    JsonObject()
                }
            }
            if (!resp.isSuccessful) {
                val msg = obj.get("error_description")?.asString ?: obj.get("msg")?.asString
                ?: obj.get("error")?.asString ?: "request failed (${resp.code})"
                throw AuthHttpException(resp.code, msg)
            }
            return obj
        }
    }

    fun signUp(email: String, password: String): Session? {
        val body = JsonObject().apply { addProperty("email", email); addProperty("password", password) }
        return parseSession(postAuth("signup", body))
    }

    fun signIn(email: String, password: String): Session {
        val body = JsonObject().apply { addProperty("email", email); addProperty("password", password) }
        return parseSession(postAuth("token?grant_type=password", body))
            ?: throw IOException("sign in did not return a session")
    }

    fun refresh(refreshToken: String): Session {
        val body = JsonObject().apply { addProperty("refresh_token", refreshToken) }
        return parseSession(postAuth("token?grant_type=refresh_token", body), refreshToken)
            ?: throw IOException("could not refresh session")
    }

    fun verifyOtp(email: String, token: String, type: String): Session {
        val body = JsonObject().apply {
            addProperty("email", email)
            addProperty("token", token)
            addProperty("type", type)
        }
        return parseSession(postAuth("verify", body)) ?: throw IOException("that code did not work")
    }

    fun resend(email: String, type: String) {
        val body = JsonObject().apply { addProperty("email", email); addProperty("type", type) }
        postAuth("resend", body)
    }

    fun recover(email: String) {
        postAuth("recover", JsonObject().apply { addProperty("email", email) })
    }

    fun updatePassword(session: Session, newPassword: String) {
        val req = Request.Builder()
            .url("$baseUrl/auth/v1/user")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", "application/json")
            .put(JsonObject().apply { addProperty("password", newPassword) }.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("could not update password (${resp.code})")
        }
    }

    fun signOut(session: Session) {
        val req = Request.Builder()
            .url("$baseUrl/auth/v1/logout?scope=local")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .post("{}".toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code !in setOf(401, 403)) {
                throw AuthHttpException(resp.code, "could not sign out (${resp.code})")
            }
        }
    }

    private fun authedGet(session: Session, path: String): String {
        val req = Request.Builder()
            .url("$baseUrl/rest/v1/$path")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RestHttpException(resp.code, "read failed (${resp.code}): $text")
            return text
        }
    }

    private fun authedGet(session: Session, url: HttpUrl): String {
        val req = Request.Builder()
            .url(url)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RestHttpException(resp.code, "read failed (${resp.code}): $text")
            return text
        }
    }

    private fun authedPost(session: Session, path: String, jsonBody: String, prefer: String) {
        val req = Request.Builder()
            .url("$baseUrl/rest/v1/$path")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", "application/json")
            .header("Prefer", prefer)
            .post(jsonBody.toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RestHttpException(resp.code, "write failed (${resp.code}): ${resp.body?.string()}")
            }
        }
    }

    private fun authedFunction(session: Session, name: String, body: JsonObject = JsonObject()): JsonObject {
        val request = Request.Builder()
            .url("$baseUrl/functions/v1/$name")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: JsonObject()
            if (!response.isSuccessful) {
                val message = parsed.get("error")?.takeIf { !it.isJsonNull }?.asString
                    ?: "billing request failed (${response.code})"
                throw RestHttpException(response.code, message)
            }
            return parsed
        }
    }

    fun billingEntitlement(session: Session): BillingEntitlement {
        val result = authedFunction(session, "sync-entitlement")
        return BillingEntitlement(
            entitled = result.get("entitled")?.asBoolean == true,
            provider = result.get("provider")?.takeIf { !it.isJsonNull }?.asString,
            validUntil = result.get("validUntil")?.takeIf { !it.isJsonNull }?.asString,
            price = result.get("price")?.takeIf { !it.isJsonNull }?.asString,
        )
    }

    fun polarBillingUrl(session: Session, manage: Boolean = false): String {
        val result = authedFunction(session, "polar-checkout", JsonObject().apply {
            addProperty("action", if (manage) "manage" else "checkout")
        })
        return result.get("url")?.takeIf { !it.isJsonNull }?.asString
            ?: throw IOException("billing did not return a checkout link")
    }

    fun verifyPlayPurchase(session: Session, purchaseToken: String): BillingEntitlement {
        val result = authedFunction(session, "play-verify", JsonObject().apply {
            addProperty("purchaseToken", purchaseToken)
        })
        return BillingEntitlement(
            entitled = result.get("entitled")?.asBoolean == true,
            provider = result.get("provider")?.takeIf { !it.isJsonNull }?.asString,
            validUntil = result.get("validUntil")?.takeIf { !it.isJsonNull }?.asString,
            price = result.get("price")?.takeIf { !it.isJsonNull }?.asString,
        )
    }

    fun getVault(session: Session): VaultRow? {
        val text = authedGet(session, "vault?user_id=eq.${session.userId}&select=kdf_salt,kdf_params,wrapped_dek")
        val arr = gson.fromJson(text, com.google.gson.JsonArray::class.java)
        if (arr.size() == 0) return null
        val row = arr[0].asJsonObject
        return VaultRow(
            saltB64 = row.get("kdf_salt").asString,
            paramsJson = row.get("kdf_params").toString(),
            wrappedB64 = row.get("wrapped_dek").asString,
        )
    }

    fun insertVault(session: Session, saltB64: String, paramsJson: String, wrappedB64: String) {
        val obj = JsonObject().apply {
            addProperty("user_id", session.userId)
            addProperty("kdf_salt", saltB64)
            add("kdf_params", gson.fromJson(paramsJson, JsonObject::class.java))
            addProperty("wrapped_dek", wrappedB64)
        }
        authedPost(session, "vault", obj.toString(), "return=minimal")
    }

    fun upsertDevice(session: Session, id: String, platform: String, label: String, fcmToken: String? = null) {
        val obj = JsonObject().apply {
            addProperty("id", id)
            addProperty("user_id", session.userId)
            addProperty("platform", platform)
            addProperty("label", label)
            addProperty("last_seen", Instant.now().toString())
            if (fcmToken != null) addProperty("fcm_token", fcmToken)
        }
        authedPost(session, "devices?on_conflict=id", "[$obj]", "resolution=merge-duplicates,return=minimal")
    }

    fun devices(session: Session): List<DeviceRow> {
        val text = authedGet(session, "devices?user_id=eq.${session.userId}&select=id,platform,label,last_seen&order=last_seen.desc")
        return gson.fromJson(text, com.google.gson.JsonArray::class.java).map { el ->
            val o = el.asJsonObject
            DeviceRow(
                id = o.get("id").asString,
                platform = o.get("platform")?.asString ?: "device",
                label = o.get("label")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                lastSeen = o.get("last_seen")?.takeIf { !it.isJsonNull }?.asString,
            )
        }
    }

    fun clearDeviceToken(session: Session, id: String) {
        val req = Request.Builder()
            .url("$baseUrl/rest/v1/devices?id=eq.$id")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .patch(JsonObject().apply { add("fcm_token", com.google.gson.JsonNull.INSTANCE) }.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RestHttpException(resp.code, "could not clear device token (${resp.code})")
        }
    }

    fun pull(
        session: Session,
        cursor: String,
        after: PullPosition? = null,
        limit: Int = 500,
    ): List<SyncRow> {
        val text = authedGet(session, buildPullUrl(session, cursor, after, limit))
        val arr = gson.fromJson(text, com.google.gson.JsonArray::class.java)
        return arr.map { el ->
            val o = el.asJsonObject
            SyncRow(
                id = o.get("id").asString,
                namespace = o.get("namespace").asString,
                recordKey = o.get("record_key").asString,
                deviceId = o.get("device_id")?.takeIf { !it.isJsonNull }?.asString,
                ciphertext = o.get("ciphertext").asString,
                version = o.get("version").asLong,
                deleted = o.get("deleted").asBoolean,
                updatedAt = o.get("updated_at").asString,
            )
        }
    }

    internal fun buildPullUrl(
        session: Session,
        cursor: String,
        after: PullPosition? = null,
        limit: Int = 500,
    ): HttpUrl = "$baseUrl/rest/v1/sync_records".toHttpUrl().newBuilder()
        .addQueryParameter("user_id", "eq.${session.userId}")
        .addQueryParameter("order", "updated_at.asc,id.asc")
        .addQueryParameter("limit", limit.coerceIn(1, 1000).toString())
        .addQueryParameter("select", "id,namespace,record_key,device_id,ciphertext,version,deleted,updated_at")
        .apply {
            if (after == null) {
                addQueryParameter("updated_at", "gte.$cursor")
            } else {
                addQueryParameter(
                    "or",
                    "(updated_at.gt.${after.updatedAt},and(updated_at.eq.${after.updatedAt},id.gt.${after.id}))",
                )
            }
        }
        .build()

    fun upsertRecord(session: Session, namespace: String, recordKey: String, deviceId: String, ciphertextB64: String, version: Long, deleted: Boolean = false) {
        val obj = JsonObject().apply {
            addProperty("user_id", session.userId)
            addProperty("namespace", namespace)
            addProperty("record_key", recordKey)
            addProperty("device_id", deviceId)
            addProperty("ciphertext", ciphertextB64)
            addProperty("version", version)
            addProperty("deleted", deleted)
        }
        authedPost(
            session,
            "sync_records?on_conflict=user_id,namespace,record_key",
            "[$obj]",
            "resolution=merge-duplicates,return=minimal",
        )
    }

    companion object {
        const val ANON_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBkaXhremhuY3V1eHV4d2hkd2RoIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODIyNzA2MjIsImV4cCI6MjA5Nzg0NjYyMn0.FfDMzEV6W5_IVuVmm_ld1zUx9wjrTE6Vuj415wHSAas"
    }
}

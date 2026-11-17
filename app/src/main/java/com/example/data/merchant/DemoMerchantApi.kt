package com.example.data.merchant

import com.example.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Free-tier DEMO merchant operations served by the existing Cloudflare Worker.
 * Firebase Functions are intentionally not used for provisioning or claiming.
 */
object DemoMerchantApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private suspend fun post(action: String, data: JSONObject = JSONObject()): JSONObject {
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IOException("Iniciá sesión en OMNI para continuar.")
        val token = user.getIdToken(false).await().token
            ?: throw IOException("No se pudo validar tu sesión OMNI. Volvé a iniciar sesión.")
        val payload = JSONObject().put("action", action).put("data", data)
        val request = Request.Builder()
            .url(BuildConfig.OMNI_WORKER_BASE_URL.trimEnd('/') + "/demo-merchant")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val parsed = runCatching { JSONObject(raw) }.getOrElse {
                    throw IOException("El servicio DEMO respondió con un formato inesperado.")
                }
                if (!response.isSuccessful || !parsed.optBoolean("ok", false)) {
                    throw IOException(parsed.optString("message").ifBlank {
                        "No se pudo completar la operación DEMO (HTTP ${response.code})."
                    })
                }
                parsed.optJSONObject("result")
                    ?: throw IOException("El servicio DEMO no devolvió el resultado esperado.")
            }
        }
    }

    suspend fun provision(): JSONObject = post("provision")

    suspend fun claim(code: String): JSONObject =
        post("claim", JSONObject().put("code", code.trim().uppercase()))

    suspend fun saveCatalog(payload: JSONObject): JSONObject = post("saveCatalog", payload)

    suspend fun createOrder(payload: JSONObject): JSONObject = post("createOrder", payload)
}

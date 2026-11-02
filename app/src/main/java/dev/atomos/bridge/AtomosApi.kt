package dev.atomos.bridge
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AuthTokens(val accessToken: String, val refreshToken: String)
class AtomosApi {
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    private fun request(path: String, body: JSONObject? = null, bearer: String? = null, device: String? = null): JSONObject {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 12000; c.readTimeout = 15000
            c.setRequestProperty("apikey", key); c.setRequestProperty("Accept", "application/json")
            bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            device?.let { c.setRequestProperty("x-device-token", it) }
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val result = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            if (status !in 200..299) throw IllegalStateException(result.optString("msg", result.optString("error", "Request failed (HTTP $status)")))
            return result
        } finally { c.disconnect() }
    }
    fun sendOtp(email: String) = request("/auth/v1/otp", JSONObject().put("email", email).put("create_user", true))
    fun verifyOtp(email: String, token: String): AuthTokens {
        val r = request("/auth/v1/verify", JSONObject().put("email", email).put("token", token).put("type", "email"))
        return AuthTokens(r.getString("access_token"), r.getString("refresh_token"))
    }
    fun refresh(token: String): AuthTokens {
        val r = request("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", token))
        return AuthTokens(r.getString("access_token"), r.getString("refresh_token"))
    }
    fun call(action: String, bearer: String? = null, device: String? = null, extra: JSONObject = JSONObject()): JSONObject =
        request("/functions/v1/atomos-device-bridge", JSONObject(extra.toString()).put("action", action), bearer, device)
}
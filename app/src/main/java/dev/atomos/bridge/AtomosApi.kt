package dev.atomos.bridge

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

data class AuthTokens(val accessToken: String, val refreshToken: String)

class ApiException(val status: Int, message: String) : IllegalStateException(message)

class AtomosApi {
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    private val redirectUri = "dev.atomos.bridge://auth-callback"
    private val maxResponseBytes = 64 * 1024

    private fun request(path: String, body: JSONObject? = null, bearer: String? = null,
                        device: String? = null): JSONObject {
        require(path.startsWith("/") && !path.startsWith("//")) { "Invalid API path." }
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 18_000
            useCaches = false
            instanceFollowRedirects = false
            doInput = true
            setRequestProperty("apikey", key)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-store")
            setRequestProperty("User-Agent", "AtomosDeviceBridge/0.2.2 Android")
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
            device?.let { setRequestProperty("x-device-token", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxResponseBytes) throw IllegalStateException("The server response was unexpectedly large.")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }.orEmpty()
            val parsed = runCatching { if (raw.isBlank()) JSONObject() else JSONObject(raw) }.getOrElse {
                JSONObject().put("message", raw.take(300))
            }
            if (status !in 200..299) {
                val message = parsed.optString("msg").ifBlank {
                    parsed.optString("message").ifBlank { parsed.optString("error").ifBlank { "Request failed (HTTP $status)." } }
                }
                throw ApiException(status, message)
            }
            return parsed
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                when (e) {
                    is java.net.SocketTimeoutException -> "The server took too long to respond. Please try again."
                    is java.net.UnknownHostException -> "No internet connection. Check your network and try again."
                    is javax.net.ssl.SSLException -> "A secure connection could not be established."
                    else -> e.message?.take(180) ?: "Network request failed."
                }, e
            )
        } finally { connection.disconnect() }
    }

    fun newPkceVerifier(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun githubAuthorizationUrl(verifier: String): String {
        require(verifier.length in 43..128) { "Invalid OAuth verifier." }
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val redirect = URLEncoder.encode(redirectUri, Charsets.UTF_8.name())
        val encodedChallenge = URLEncoder.encode(challenge, Charsets.UTF_8.name())
        return "$base/auth/v1/authorize?provider=github&redirect_to=$redirect&code_challenge=$encodedChallenge&code_challenge_method=s256"
    }

    fun exchangeGitHubCode(code: String, verifier: String): AuthTokens {
        require(code.length in 1..2048 && verifier.length in 43..128) { "Invalid GitHub callback." }
        val r = request("/auth/v1/token?grant_type=pkce",
            JSONObject().put("auth_code", code).put("code_verifier", verifier))
        return AuthTokens(r.getString("access_token"), r.getString("refresh_token"))
    }

    fun refresh(token: String): AuthTokens {
        val r = request("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", token))
        return AuthTokens(r.getString("access_token"), r.getString("refresh_token"))
    }

    fun call(action: String, bearer: String? = null, device: String? = null,
             extra: JSONObject = JSONObject()): JSONObject {
        val body = JSONObject(extra.toString()).put("action", action)
        return request("/functions/v1/atomos-device-bridge", body, bearer, device)
    }

    /** Refreshes an expired user session once. Never automatically retries device claims. */
    fun callAsUser(action: String, store: SecureStore, extra: JSONObject = JSONObject()): JSONObject {
        var access = store.get("access") ?: throw IllegalStateException("Please sign in again.")
        try { return call(action, access, extra = extra) }
        catch (e: ApiException) {
            if (e.status != 401) throw e
            val refreshToken = store.get("refresh") ?: run {
                store.remove("access", "refresh")
                throw IllegalStateException("Your session expired. Please sign in again.")
            }
            val tokens = try { refresh(refreshToken) } catch (_: Exception) {
                store.remove("access", "refresh")
                throw IllegalStateException("Your session expired. Please sign in again.")
            }
            store.put("access", tokens.accessToken)
            store.put("refresh", tokens.refreshToken)
            access = tokens.accessToken
            return try { call(action, access, extra = extra) } catch (retry: ApiException) {
                if (retry.status == 401) {
                    store.remove("access", "refresh")
                    throw IllegalStateException("Your session expired. Please sign in again.")
                }
                throw retry
            }
        }
    }
}

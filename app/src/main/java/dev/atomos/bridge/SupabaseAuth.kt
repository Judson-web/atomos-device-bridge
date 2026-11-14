package dev.atomos.bridge

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

data class SessionTokens(val access: String, val refresh: String)
data class AuthUser(val id: String, val email: String?, val name: String?, val avatar: String?)

class SupabaseAuth {
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    private val redirect = "dev.atomos.bridge://auth-callback"

    private fun requireConfiguration() {
        require(base.startsWith("https://") && key.startsWith("sb_publishable_")) {
            "Supabase authentication is not configured for this build."
        }
    }

    private fun request(
        path: String,
        method: String = "POST",
        body: JSONObject? = null,
        bearer: String? = null
    ): JSONObject {
        requireConfiguration()
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 18_000
            useCaches = false
            setRequestProperty("apikey", key)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-store")
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText().take(65_536) }
                .orEmpty()
            val parsed = runCatching { JSONObject(raw) }
                .getOrElse { JSONObject().put("message", raw.take(300)) }
            if (status !in 200..299) {
                error(
                    parsed.optString("msg").ifBlank {
                        parsed.optString("message").ifBlank {
                            parsed.optString("error_description").ifBlank {
                                "Authentication request failed ($status)"
                            }
                        }
                    }
                )
            }
            return parsed
        } finally {
            connection.disconnect()
        }
    }

    fun verifier(): String = Base64.encodeToString(
        ByteArray(48).also { SecureRandom().nextBytes(it) },
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )

    fun discordUrl(verifier: String): String {
        requireConfiguration()
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$base/auth/v1/authorize?provider=discord" +
            "&redirect_to=${URLEncoder.encode(redirect, "UTF-8")}" +
            "&code_challenge=${URLEncoder.encode(challenge, "UTF-8")}" +
            "&code_challenge_method=s256"
    }

    fun exchange(code: String, verifier: String): SessionTokens {
        require(code.isNotBlank() && verifier.isNotBlank())
        val response = request(
            "/auth/v1/token?grant_type=pkce",
            body = JSONObject()
                .put("auth_code", code)
                .put("code_verifier", verifier)
        )
        return SessionTokens(
            response.getString("access_token"),
            response.getString("refresh_token")
        )
    }

    fun user(access: String): AuthUser {
        val response = request(
            "/functions/v1/atomos-app-api",
            body = JSONObject().put("action", "profile"),
            bearer = access
        )
        if (response.optString("provider") != "discord") {
            error("Discord authentication is required for Atomos.")
        }
        return AuthUser(
            id = response.getString("id"),
            email = response.optString("email").ifBlank { null },
            name = response.optString("display_name").ifBlank { "Atomos user" },
            avatar = response.optString("avatar_url").ifBlank { null }
        )
    }

    fun refresh(refreshToken: String): SessionTokens {
        val response = request(
            "/auth/v1/token?grant_type=refresh_token",
            body = JSONObject().put("refresh_token", refreshToken)
        )
        return SessionTokens(
            response.getString("access_token"),
            response.getString("refresh_token")
        )
    }
}

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
data class ApiException(val status: Int, override val message: String): IllegalStateException(message)

class AtomosApi {
    private val base=BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key=BuildConfig.SUPABASE_PUBLISHABLE_KEY
    private val redirectUri="dev.atomos.bridge://auth-callback"
    private val maxResponseBytes=64*1024
    private fun request(path:String,body:JSONObject?=null,bearer:String?=null,device:String?=null):JSONObject {
        require(path.startsWith("/")&&!path.startsWith("//"))
        val c=(URL(base+path).openConnection() as HttpURLConnection).apply {
            requestMethod="POST";connectTimeout=12000;readTimeout=18000;useCaches=false;instanceFollowRedirects=false;doInput=true
            setRequestProperty("apikey",key);setRequestProperty("Accept","application/json");setRequestProperty("Cache-Control","no-store")
            bearer?.let { setRequestProperty("Authorization","Bearer $it") };device?.let { setRequestProperty("x-device-token",it) }
            if(body!=null){doOutput=true;setRequestProperty("Content-Type","application/json; charset=utf-8")}
        }
        try {
            if(body!=null)c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status=c.responseCode;val stream=if(status in 200..299)c.inputStream else c.errorStream
            val raw=stream?.use { input -> val out=ByteArrayOutputStream();val buf=ByteArray(4096);var total=0
                while(true){val n=input.read(buf);if(n<0)break;total+=n;if(total>maxResponseBytes)error("Response too large");out.write(buf,0,n)};out.toString(Charsets.UTF_8.name()) }.orEmpty()
            val parsed=runCatching { if(raw.isBlank())JSONObject() else JSONObject(raw) }.getOrElse { JSONObject().put("message",raw.take(300)) }
            if(status !in 200..299)throw ApiException(status,parsed.optString("message").ifBlank { parsed.optString("error").ifBlank { "Request failed ($status)" } })
            return parsed
        } finally { c.disconnect() }
    }
    fun newPkceVerifier():String=Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) },Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun githubAuthorizationUrl(verifier:String):String {
        val challenge=Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "$base/auth/v1/authorize?provider=github&redirect_to=${URLEncoder.encode(redirectUri,Charsets.UTF_8.name())}&code_challenge=${URLEncoder.encode(challenge,Charsets.UTF_8.name())}&code_challenge_method=s256"
    }
    fun exchangeGitHubCode(code:String,verifier:String):AuthTokens {
        val r=request("/auth/v1/token?grant_type=pkce",JSONObject().put("auth_code",code).put("code_verifier",verifier))
        return AuthTokens(r.getString("access_token"),r.getString("refresh_token"))
    }
    fun refresh(token:String):AuthTokens { val r=request("/auth/v1/token?grant_type=refresh_token",JSONObject().put("refresh_token",token));return AuthTokens(r.getString("access_token"),r.getString("refresh_token")) }
    fun call(action:String,bearer:String?=null,device:String?=null,extra:JSONObject=JSONObject()):JSONObject =
        request("/functions/v1/atomos-device-bridge",JSONObject(extra.toString()).put("action",action),bearer,device)
    fun callAsUser(action:String,store:SecureStore,extra:JSONObject=JSONObject()):JSONObject {
        var access=store.get("access")?:error("Please sign in again.")
        try{return call(action,access,extra=extra)}catch(e:ApiException){
            if(e.status!=401)throw e
            val refreshToken=store.get("refresh")?:run { store.remove("access","refresh");error("Your session expired. Please sign in again.") }
            val tokens=try{refresh(refreshToken)}catch(_:Exception){store.remove("access","refresh");error("Your session expired. Please sign in again.")}
            store.put("access",tokens.accessToken);store.put("refresh",tokens.refreshToken);access=tokens.accessToken
            return call(action,access,extra=extra)
        }
    }
}
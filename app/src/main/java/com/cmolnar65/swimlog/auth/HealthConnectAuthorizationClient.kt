package com.cmolnar65.swimlog.auth

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class PendingAuthorization(val state: String, val verifier: String)

internal data class AuthorizationRequest(
    val pending: PendingAuthorization,
    val url: String,
)

internal sealed class AuthorizationCallback {
    data class Code(val value: String) : AuthorizationCallback()
    object Cancelled : AuthorizationCallback()
    object Invalid : AuthorizationCallback()
}

/** OAuth authorization-code + PKCE contract implemented by the Swim Log WordPress receiver. */
internal object HealthConnectAuthorizationProtocol {
    const val CLIENT_ID = "swimlog_android"
    const val REDIRECT_URI = "com.cmolnar65.swimlog:/oauth2redirect"
    private const val AUTHORIZE_PAGE = "/wp-admin/admin.php"
    private const val AUTHORIZE_SLUG = "swimlog-health-connect-authorize"

    fun createRequest(site: String, secureRandom: SecureRandom = SecureRandom()): AuthorizationRequest {
        val verifier = base64Url(ByteArray(32).also(secureRandom::nextBytes))
        val state = base64Url(ByteArray(32).also(secureRandom::nextBytes))
        val challenge = challengeForVerifier(verifier)
        val query = listOf(
            "page" to AUTHORIZE_SLUG,
            "response_type" to "code",
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
        ).joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        return AuthorizationRequest(PendingAuthorization(state, verifier), "${site.trimEnd('/')}$AUTHORIZE_PAGE?$query")
    }

    fun parseCallback(rawUri: String, expectedState: String): AuthorizationCallback {
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return AuthorizationCallback.Invalid
        if (!uri.scheme.equals("com.cmolnar65.swimlog", ignoreCase = true) ||
            uri.rawAuthority != null || uri.path != "/oauth2redirect" || uri.rawFragment != null
        ) return AuthorizationCallback.Invalid

        val parameters = parseQuery(uri.rawQuery) ?: return AuthorizationCallback.Invalid
        if (parameters.keys != setOf("state", if (parameters.containsKey("code")) "code" else "error")) {
            return AuthorizationCallback.Invalid
        }
        if (parameters["state"] != expectedState) return AuthorizationCallback.Invalid
        val code = parameters["code"]
        if (code != null && CODE_PATTERN.matches(code)) return AuthorizationCallback.Code(code)
        if (code == null && !parameters["error"].isNullOrBlank()) return AuthorizationCallback.Cancelled
        return AuthorizationCallback.Invalid
    }

    fun challengeForVerifier(verifier: String): String {
        require(VERIFIER_PATTERN.matches(verifier)) { "Invalid PKCE verifier" }
        return base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }

    internal fun tokenRequestJson(code: String, verifier: String): String = JSON.encodeToString(
        TokenRequest(
            grantType = "authorization_code",
            clientId = CLIENT_ID,
            redirectUri = REDIRECT_URI,
            code = code,
            codeVerifier = verifier,
        ),
    )

    internal fun parseTokenResponse(response: String): String {
        val parsed = runCatching { JSON.decodeFromString<TokenResponse>(response) }.getOrNull()
            ?: throw AuthorizationTokenException()
        if (!parsed.tokenType.equals("Bearer", ignoreCase = true) || !TOKEN_PATTERN.matches(parsed.accessToken)) {
            throw AuthorizationTokenException()
        }
        return parsed.accessToken
    }

    private fun parseQuery(query: String?): Map<String, String>? {
        if (query.isNullOrEmpty()) return null
        val values = linkedMapOf<String, String>()
        for (part in query.split('&')) {
            val separator = part.indexOf('=')
            if (separator <= 0) return null
            val key = runCatching { URLDecoder.decode(part.substring(0, separator), "UTF-8") }.getOrNull() ?: return null
            val value = runCatching { URLDecoder.decode(part.substring(separator + 1), "UTF-8") }.getOrNull() ?: return null
            if (key in values) return null
            values[key] = value
        }
        return values
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private val CODE_PATTERN = Regex("^slhca_[A-Za-z0-9_-]{43}$")
    private val TOKEN_PATTERN = Regex("^slhc_[A-Za-z0-9_-]{43}$")
    private val VERIFIER_PATTERN = Regex("^[A-Za-z0-9._~-]{43,128}$")
    private val JSON = Json { ignoreUnknownKeys = true }
}

/** Exchanges the short-lived code over HTTPS without logging request or response contents. */
internal class HealthConnectAuthorizationClient(
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun exchange(site: String, code: String, verifier: String): String = withContext(Dispatchers.IO) {
        val connection = connectionFactory(URL(site.trimEnd('/') + "/wp-json/swimlog/v1/health-connect/token"))
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            val body = HealthConnectAuthorizationProtocol.tokenRequestJson(code, verifier).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode !in 200..299) throw AuthorizationTokenException(connection.responseCode)
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            HealthConnectAuthorizationProtocol.parseTokenResponse(response)
        } catch (failure: AuthorizationTokenException) {
            throw failure
        } catch (_: IOException) {
            throw AuthorizationNetworkException()
        } catch (_: IllegalArgumentException) {
            throw AuthorizationTokenException()
        } finally {
            connection.disconnect()
        }
    }
}

internal class AuthorizationTokenException(val httpStatus: Int? = null) : Exception()
internal class AuthorizationNetworkException : Exception()

@Serializable
private data class TokenRequest(
    @SerialName("grant_type") val grantType: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("code") val code: String,
    @SerialName("code_verifier") val codeVerifier: String,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
)

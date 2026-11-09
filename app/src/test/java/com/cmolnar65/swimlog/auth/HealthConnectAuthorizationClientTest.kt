package com.cmolnar65.swimlog.auth

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectAuthorizationClientTest {
    private val state = "A".repeat(43)
    private val code = "slhca_" + "B".repeat(43)
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

    @Test
    fun usesTheRfc7636S256Challenge() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            HealthConnectAuthorizationProtocol.challengeForVerifier(verifier),
        )
    }

    @Test
    fun browserRequestUsesFixedOwnerConsentAndPkceParameters() {
        val request = HealthConnectAuthorizationProtocol.createRequest("https://cmolnar.com")
        val uri = URL(request.url)

        assertEquals("cmolnar.com", uri.host)
        assertEquals("/wp-admin/admin.php", uri.path)
        assertTrue(uri.query.contains("page=swimlog-health-connect-authorize"))
        assertTrue(uri.query.contains("response_type=code"))
        assertTrue(uri.query.contains("client_id=swimlog_android"))
        assertTrue(uri.query.contains("redirect_uri=com.cmolnar65.swimlog%3A%2Foauth2redirect"))
        assertTrue(uri.query.contains("code_challenge_method=S256"))
        assertTrue(request.pending.state.matches(Regex("^[A-Za-z0-9_-]{43}$")))
        assertTrue(request.pending.verifier.matches(Regex("^[A-Za-z0-9_-]{43}$")))
        assertFalse(request.url.contains(request.pending.verifier))
    }

    @Test
    fun acceptsOnlyTheExpectedCallbackAndState() {
        val uri = "com.cmolnar65.swimlog:/oauth2redirect?code=$code&state=$state"

        assertEquals(AuthorizationCallback.Code(code), HealthConnectAuthorizationProtocol.parseCallback(uri, state))
        assertEquals(AuthorizationCallback.Invalid, HealthConnectAuthorizationProtocol.parseCallback(uri, "C".repeat(43)))
        assertEquals(
            AuthorizationCallback.Invalid,
            HealthConnectAuthorizationProtocol.parseCallback("https://evil.example/?code=$code&state=$state", state),
        )
        assertEquals(
            AuthorizationCallback.Invalid,
            HealthConnectAuthorizationProtocol.parseCallback("com.cmolnar65.swimlog:/oauth2redirect?code=$code&code=$code&state=$state", state),
        )
    }

    @Test
    fun recognizesUserCancellationOnlyAfterStateMatches() {
        val uri = "com.cmolnar65.swimlog:/oauth2redirect?error=access_denied&state=$state"

        assertEquals(AuthorizationCallback.Cancelled, HealthConnectAuthorizationProtocol.parseCallback(uri, state))
        assertEquals(AuthorizationCallback.Invalid, HealthConnectAuthorizationProtocol.parseCallback(uri, "D".repeat(43)))
    }

    @Test
    fun exchangesCodeAtTheFixedHttpsTokenEndpointWithoutBearerHeader() = runBlocking {
        val token = "slhc_" + "Z".repeat(43)
        val fake = RecordingConnection(URL("https://cmolnar.com/wp-json/swimlog/v1/health-connect/token"),
            """{"access_token":"$token","token_type":"Bearer"}""")
        var opened: URL? = null
        val client = HealthConnectAuthorizationClient { url -> opened = url; fake }

        val result = client.exchange("https://cmolnar.com", code, verifier)

        assertEquals(token, result)
        assertEquals("https://cmolnar.com/wp-json/swimlog/v1/health-connect/token", opened.toString())
        assertEquals("POST", fake.requestMethod)
        assertEquals("application/json; charset=utf-8", fake.getRequestProperty("Content-Type"))
        assertEquals("no-store", fake.getRequestProperty("Cache-Control"))
        val body = fake.body.toString(Charsets.UTF_8.name())
        assertTrue(body.contains("\"grant_type\":\"authorization_code\""))
        assertTrue(body.contains("\"client_id\":\"swimlog_android\""))
        assertTrue(body.contains("\"code\":\"$code\""))
        assertTrue(body.contains("\"code_verifier\":\"$verifier\""))
        assertFalse(body.contains("owner_id"))
        assertEquals(null, fake.getRequestProperty("Authorization"))
        assertTrue(fake.disconnected)
    }

    @Test
    fun rejectsInvalidTokenResponsesWithoutReturningTheBody() {
        val error = runCatching {
            HealthConnectAuthorizationProtocol.parseTokenResponse("""{"access_token":"private","token_type":"Basic"}""")
        }.exceptionOrNull()

        assertTrue(error is AuthorizationTokenException)
        assertFalse(error.toString().contains("private"))
    }

    private class RecordingConnection(url: URL, private val response: String) : HttpURLConnection(url) {
        val body = ByteArrayOutputStream()
        var disconnected = false

        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = 200
        override fun getOutputStream(): ByteArrayOutputStream = body
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
    }
}

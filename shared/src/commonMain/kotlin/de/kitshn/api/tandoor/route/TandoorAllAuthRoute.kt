package de.kitshn.api.tandoor.route

import de.kitshn.api.tandoor.TandoorClient
import de.kitshn.api.tandoor.TandoorRequestsError
import de.kitshn.api.tandoor.reqAny
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.parseServerSetCookieHeader
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Uses django-allauth headless API (https://docs.allauth.org/en/latest/headless/),
 * which replaces Tandoor's outdated `api-token-auth` endpoint.
 *
 * This deliberately drives the "browser" client, not the "app" client: the
 * X-Session-Token header does not work for auth (yet), so the cookie approach
 * is used instead.
 */
class TandoorAllAuthRoute(client: TandoorClient) : TandoorBaseRoute(client) {

    private fun extractCookies(response: HttpResponse): Map<String, String> =
        response.headers.getAll(HttpHeaders.SetCookie).orEmpty().associate {
            val cookie = parseServerSetCookieHeader(it)
            cookie.name to cookie.value
        }

    /** Returns a `credentials.cookie`-compatible cookie string on success, `null` on invalid credentials. */
    suspend fun login(): String? {
        // Django's CSRF middleware requires a token to already be present
        // before it'll accept the login POST below, so prime one first.
        val csrfResponse = try {
            client.reqAny("@_allauth/browser/v1/auth/session", HttpMethod.Get)
        } catch(e: TandoorRequestsError) {
            e.response ?: return null
        }

        val csrfToken = extractCookies(csrfResponse)["csrftoken"] ?: return null

        val obj = buildJsonObject {
            put("username", client.credentials.username)
            put("password", client.credentials.password)
        }

        try {
            val loginResponse = client.reqAny(
                endpoint = "@_allauth/browser/v1/auth/login",
                _method = HttpMethod.Post,
                data = obj.toString(),
                contentType = ContentType.Application.Json,
                custom = {
                    headers {
                        append("Cookie", "csrftoken=$csrfToken")
                        append("X-CSRFToken", csrfToken)
                    }
                }
            )

            val cookies = extractCookies(loginResponse)
            val sessionId = cookies["sessionid"] ?: return null
            val newCsrfToken = cookies["csrftoken"] ?: csrfToken

            return "sessionid=$sessionId; csrftoken=$newCsrfToken"
        } catch(_: TandoorRequestsError) {
        }

        return null
    }

}

package dev.brahmkshatriya.echo.extension.utils

import okhttp3.Request
import dev.brahmkshatriya.echo.common.settings.Settings

/**
 * Utility class for building consistent HTTP requests across DAB API calls
 */
class RequestUtils(private val settings: Settings) {

    /** Extract session cookie from settings */
    fun getCookieHeaderValue(): String? {
        val raw = settings.getString("session_cookie") ?: return null
        val firstPart = raw.split(';').firstOrNull()?.trim() ?: return null
        if (firstPart.isEmpty()) return null
        return if (firstPart.contains('=')) firstPart else "session=$firstPart"
    }

    /** Check if user is logged in */
    fun isLoggedIn(): Boolean = !getCookieHeaderValue().isNullOrBlank()

    /** Create HTTP request builder with standard headers */
    fun newRequestBuilder(url: String, includeCookie: Boolean = true): Request.Builder {
        val rb = Request.Builder().url(url)
        if (includeCookie) {
            val cookie = getCookieHeaderValue()
            if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
        }
        rb.header("Accept", "application/json")
          .header("User-Agent", "EchoDAB-Extension/1.0")
        return rb
    }

    /** Clear session when authentication fails */
    fun clearSessionOnAuthFailure(responseCode: Int) {
        if (responseCode == 401 || responseCode == 403) {
            settings.putString("session_cookie", null)
        }
    }
}

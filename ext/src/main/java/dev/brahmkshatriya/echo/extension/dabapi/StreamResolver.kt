package dev.brahmkshatriya.echo.extension.dabapi

import kotlinx.serialization.json.Json
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.net.URL
import okhttp3.Request

class StreamResolver(
    private val httpClient: OkHttpClient,
    private val json: Json,
    @Suppress("UNUSED_PARAMETER") private val settings: Settings
) {
    /** Normalize track ID or extract id from a URL */
    private fun normalize(trackId: String?): String? {
        if (trackId == null) return null
        val s = trackId.trim()
        if (s.isEmpty()) return null
        return try {
            if (s.startsWith("http", true)) {
                val q = URL(s).query ?: return s
                val pairs = q.split('&')
                for (p in pairs) {
                    val kv = p.split('=')
                    if (kv.size >= 2 && kv[0].equals("trackId", true)) return kv[1]
                }
                s
            } else s
        } catch (_: Throwable) { s }
    }

    /** Resolve a stream URL directly without any caching */
    fun getStreamUrl(trackId: String): String? {
        val norm = normalize(trackId) ?: return null
        val baseUrl = if (norm.startsWith("http", true)) norm else "https://dab.yeet.su/api/stream?trackId=${URLEncoder.encode(norm, "UTF-8") }"
        var result: String? = null
        try {
            val req = Request.Builder().url(baseUrl).build()
            httpClient.newCall(req).execute().use { resp ->
                // Redirect / different effective URL
                runCatching {
                    val eff = resp.request.url.toString()
                    if (!eff.equals(baseUrl, true) && eff.startsWith("http") && !eff.contains("/api/stream")) {
                        result = eff; return@use
                    }
                }
                // Location header
                val loc = resp.header("Location")
                if (!loc.isNullOrBlank()) { result = loc; return@use }
                if (!resp.isSuccessful) return@use
                val body = resp.body?.string() ?: return@use
                // Try JSON structure
                runCatching {
                    val sr = json.decodeFromString(dev.brahmkshatriya.echo.extension.models.DabStreamResponse.serializer(), body)
                    val resolved = sr.streamUrl ?: sr.url ?: sr.stream ?: sr.link
                    if (!resolved.isNullOrBlank()) { result = resolved; return@use }
                }
                // Fallback: raw body as URL
                val trimmed = body.trim()
                if (trimmed.startsWith("http")) result = trimmed
            }
        } catch (_: Throwable) { return null }
        return result ?: baseUrl
    }
}
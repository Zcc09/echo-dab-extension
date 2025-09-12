package dev.brahmkshatriya.echo.extension.dabapi

import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.Request
import dev.brahmkshatriya.echo.extension.Converter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class FavoritesService(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val settings: Settings,
    private val converter: Converter
) {
    private fun cookieHeaderValue(): String? {
        val raw = settings.getString("session_cookie") ?: return null
        val firstPart = raw.split(';').firstOrNull()?.trim() ?: return null
        if (firstPart.isEmpty()) return null
        return if (firstPart.contains('=')) firstPart else "session=$firstPart"
    }

    private fun fetchFavoritesFromUrl(url: String, authenticated: Boolean = true): List<Track> {
        try {
            val requestBuilder = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "EchoDAB-Extension/1.0")

            if (authenticated) {
                val cookie = cookieHeaderValue()
                if (cookie.isNullOrBlank()) return emptyList()
                requestBuilder.header("Cookie", cookie)
            }

            httpClient.newCall(requestBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()

                // Try typed decode first
                try {
                    val trackResponse: dev.brahmkshatriya.echo.extension.models.DabTrackResponse = json.decodeFromString(body)
                    return trackResponse.tracks.map { converter.toTrack(it) }
                } catch (_: Throwable) { }

                // Try parsing from JSON structure
                try {
                    val root = json.parseToJsonElement(body)
                    return parseTracksFromJson(root)
                } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
        return emptyList()
    }

    private fun parseTracksFromJson(root: kotlinx.serialization.json.JsonElement): List<Track> {
        return when (root) {
            is JsonObject -> {
                val arr = (root["favorites"] as? JsonArray)
                    ?: (root["tracks"] as? JsonArray)
                    ?: (root["data"] as? JsonArray)
                    ?: (root["items"] as? JsonArray)
                arr?.mapNotNull { el ->
                    if (el is JsonObject) tryParseTrack(el) else null
                } ?: emptyList()
            }
            is JsonArray -> root.mapNotNull { el ->
                if (el is JsonObject) tryParseTrack(el) else null
            }
            else -> emptyList()
        }
    }

    private fun tryParseTrack(el: JsonObject): Track? {
        return try {
            val dt = json.decodeFromJsonElement(dev.brahmkshatriya.echo.extension.models.DabTrack.serializer(), el)
            converter.toTrack(dt)
        } catch (_: Throwable) { null }
    }

    fun getFavorites(limit: Int = 200, offset: Int = 0): List<Track> {
        val url = "https://dab.yeet.su/api/favorites?limit=$limit&offset=$offset"

        // Try unauthenticated first, then authenticated fallback
        val unauthResult = fetchFavoritesFromUrl(url, authenticated = false)
        return if (unauthResult.isNotEmpty()) unauthResult else fetchFavoritesFromUrl(url, authenticated = true)
    }

    fun getFavoritesAuthenticated(limit: Int = 200, offset: Int = 0): List<Track> {
        val url = "https://dab.yeet.su/api/favorites?limit=$limit&offset=$offset"
        return fetchFavoritesFromUrl(url, authenticated = true)
    }

    fun fetchFavoritesPage(pageIndex: Int = 1, pageSize: Int = 200): List<Track> {
        val limit = pageSize
        val offset = (pageIndex - 1) * pageSize
        return try {
            val cookie = cookieHeaderValue()
            if (!cookie.isNullOrBlank()) getFavoritesAuthenticated(limit, offset) else getFavorites(limit, offset)
        } catch (_: Throwable) {
            getFavorites(limit, offset)
        }
    }
}

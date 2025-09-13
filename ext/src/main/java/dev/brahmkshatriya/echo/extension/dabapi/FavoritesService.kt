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

    private fun isLoggedIn(): Boolean {
        return !cookieHeaderValue().isNullOrBlank()
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
                if (!resp.isSuccessful) {
                    // If unauthorized and we were trying authenticated request, clear invalid session
                    if (authenticated && (resp.code == 401 || resp.code == 403)) {
                        settings.putString("session_cookie", null)
                    }
                    return emptyList()
                }
                val body = resp.body?.string() ?: return emptyList()

                // Use centralized JSON parsing
                return parseTracksFromJson(body)
            }
        } catch (_: Throwable) { }
        return emptyList()
    }

    // Centralized JSON parsing method that combines logic from ParserService
    private fun parseTracksFromJson(body: String): List<Track> {
        // Try typed decode first
        try {
            val trackResponse: dev.brahmkshatriya.echo.extension.models.DabTrackResponse = json.decodeFromString(body)
            return trackResponse.tracks.map { converter.toTrack(it) }
        } catch (_: Throwable) { }

        // Try parsing from JSON structure using enhanced logic
        try {
            val root = json.parseToJsonElement(body)
            return parseTracksFromJsonElement(root)
        } catch (_: Throwable) { }

        return emptyList()
    }

    // Enhanced JSON element parsing (integrated from ParserService)
    private fun parseTracksFromJsonElement(root: kotlinx.serialization.json.JsonElement): List<Track> {
        val out = mutableListOf<Track>()

        fun visit(el: kotlinx.serialization.json.JsonElement) {
            when (el) {
                is JsonObject -> {
                    // Try direct track object parsing with enhanced fallback
                    val direct = tryParseElementAsTrack(el)
                    if (direct != null) {
                        out.add(converter.toTrack(direct))
                        return
                    }

                    // Check common array containers
                    val candidates = listOf("favorites", "tracks", "data", "items", "results")
                    for (key in candidates) {
                        val arr = el[key] as? JsonArray
                        if (arr != null) {
                            for (item in arr) {
                                if (item is JsonObject) {
                                    val parsed = tryParseElementAsTrack(item)
                                    if (parsed != null) out.add(converter.toTrack(parsed))
                                }
                            }
                            if (out.isNotEmpty()) return
                        }
                    }
                }
                is JsonArray -> {
                    for (item in el) {
                        if (item is JsonObject) {
                            val parsed = tryParseElementAsTrack(item)
                            if (parsed != null) out.add(converter.toTrack(parsed))
                        }
                    }
                }
                else -> { /* Handle other types if needed */ }
            }
        }

        try { visit(root) } catch (_: Throwable) { }
        return out
    }

    // Enhanced track parsing with comprehensive fallback (integrated from ParserService)
    private fun tryParseElementAsTrack(el: JsonObject): dev.brahmkshatriya.echo.extension.models.DabTrack? {
        return try {
            // Try typed parsing first
            json.decodeFromJsonElement(dev.brahmkshatriya.echo.extension.models.DabTrack.serializer(), el)
        } catch (_: Throwable) {
            // Enhanced manual fallback with more field variations
            val idEl = el["id"] ?: el["trackId"] ?: el["track_id"] ?: el["track"] ?: return null
            val idStr = (idEl as? kotlinx.serialization.json.JsonPrimitive)?.content ?: idEl.toString()
            val idInt = idStr.toIntOrNull() ?: return null

            val title = (el["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: (el["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val artist = (el["artist"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: (el["artistName"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val artistId = (el["artistId"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            val albumTitle = (el["albumTitle"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: (el["album"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val albumCover = (el["albumCover"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val albumId = (el["albumId"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val releaseDate = (el["releaseDate"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val genre = (el["genre"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val duration = (el["duration"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0

            dev.brahmkshatriya.echo.extension.models.DabTrack(
                id = idInt,
                title = title,
                artist = artist,
                artistId = artistId,
                albumTitle = albumTitle,
                albumCover = albumCover,
                albumId = albumId,
                releaseDate = releaseDate,
                genre = genre,
                duration = duration,
                audioQuality = null
            )
        }
    }

    fun getFavorites(limit: Int = 200, offset: Int = 0): List<Track> {
        val url = "https://dab.yeet.su/api/favorites?limit=$limit&offset=$offset"

        // Only try authenticated if we have a session, don't fall back to unauthenticated
        return if (isLoggedIn()) {
            fetchFavoritesFromUrl(url, authenticated = true)
        } else {
            emptyList() // Return empty list if not logged in
        }
    }

    fun getFavoritesAuthenticated(limit: Int = 200, offset: Int = 0): List<Track> {
        val url = "https://dab.yeet.su/api/favorites?limit=$limit&offset=$offset"

        return if (isLoggedIn()) {
            fetchFavoritesFromUrl(url, authenticated = true)
        } else {
            emptyList()
        }
    }

    fun fetchFavoritesPage(pageIndex: Int = 1, pageSize: Int = 200): List<Track> {
        val limit = pageSize
        val offset = (pageIndex - 1) * pageSize

        // Always require authentication for favorites
        return if (isLoggedIn()) {
            getFavoritesAuthenticated(limit, offset)
        } else {
            emptyList()
        }
    }
}

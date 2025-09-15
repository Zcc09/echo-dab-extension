package dev.brahmkshatriya.echo.extension.dabapi

import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.Converter
import dev.brahmkshatriya.echo.extension.utils.RequestUtils
import dev.brahmkshatriya.echo.extension.utils.JsonParsingUtils

class FavoritesService(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val settings: Settings,
    private val converter: Converter
) {
    // Use consolidated utilities
    private val requestUtils: RequestUtils by lazy { RequestUtils(settings) }
    private val jsonParsingUtils: JsonParsingUtils by lazy { JsonParsingUtils(json, converter) }

    // Cache full favorites list because server endpoint currently returns all favorites regardless of limit/offset
    private var favoritesCache: Pair<Long, List<Track>>? = null
    private val CACHE_TTL_MS = 60_000L

    private fun invalidateInternal() { favoritesCache = null }
    fun invalidateCache() = invalidateInternal()

    private fun loadAllFavorites(): List<Track> {
        // If not logged in, clear and return empty
        if (!requestUtils.isLoggedIn()) {
            invalidateInternal()
            return emptyList()
        }
        val now = System.currentTimeMillis()
        val cached = favoritesCache
        if (cached != null && now - cached.first < CACHE_TTL_MS) return cached.second
        val list = fetchFavoritesFromUrl("https://dab.yeet.su/api/favorites", authenticated = true)
        favoritesCache = now to list
        return list
    }

    /** Fetch favorites from DAB API endpoint */
    private fun fetchFavoritesFromUrl(url: String, authenticated: Boolean = true): List<Track> {
        if (authenticated && !requestUtils.isLoggedIn()) return emptyList()
        return try {
            val req = requestUtils.newRequestBuilder(url, authenticated).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    requestUtils.clearSessionOnAuthFailure(resp.code)
                    return emptyList()
                }
                val body = resp.body?.string() ?: return emptyList()
                parseTracksFromJson(body)
            }
        } catch (_: Throwable) { emptyList() }
    }

    /** Parse tracks from JSON response using consolidated utilities */
    private fun parseTracksFromJson(body: String): List<Track> {
        try {
            val root = json.parseToJsonElement(body)
            val parsed = jsonParsingUtils.parseTracksFromResponse(root)
            if (parsed.isNotEmpty()) return parsed
        } catch (_: Throwable) { }
        // Fallback for DAB-specific track response format
        return try {
            val trackResponse: dev.brahmkshatriya.echo.extension.models.DabTrackResponse = json.decodeFromString(body)
            trackResponse.tracks.map { converter.toTrack(it) }
        } catch (_: Throwable) { emptyList() }
    }

    /** Get user favorites (full list cached), legacy params ignored */
    fun getFavorites(limit: Int = 200, offset: Int = 0): List<Track> = loadAllFavorites()

    /** Get authenticated user favorites */
    fun getFavoritesAuthenticated(limit: Int = 200, offset: Int = 0): List<Track> = loadAllFavorites()
}

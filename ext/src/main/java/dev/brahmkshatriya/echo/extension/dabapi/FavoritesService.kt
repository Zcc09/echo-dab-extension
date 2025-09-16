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
    companion object {
        private const val FAVORITES_ENDPOINT = "https://dab.yeet.su/api/favorites"
        private const val CACHE_TTL_MS = 60_000L
    }

    // Use consolidated utilities
    private val requestUtils: RequestUtils by lazy { RequestUtils(settings) }
    private val jsonParsingUtils: JsonParsingUtils by lazy { JsonParsingUtils(json, converter) }

    // Cache full favorites list because server endpoint currently returns all favorites regardless of limit/offset
    private var favoritesCache: Pair<Long, List<Track>>? = null

    private fun invalidateInternal() { favoritesCache = null }
    fun invalidateCache() = invalidateInternal()

    private fun loadAllFavorites(): List<Track> {
        if (!requestUtils.isLoggedIn()) { // not logged in
            invalidateInternal(); return emptyList()
        }
        val now = System.currentTimeMillis()
        favoritesCache?.let { (ts, list) ->
            if (now - ts < CACHE_TTL_MS) return list
        }
        val list = fetchFavoritesFromUrl(FAVORITES_ENDPOINT, authenticated = true)
        favoritesCache = now to list
        return list
    }

    /** Fetch favorites from DAB API endpoint */
    private fun fetchFavoritesFromUrl(@Suppress("UNUSED_PARAMETER") url: String, @Suppress("UNUSED_PARAMETER") authenticated: Boolean = true): List<Track> {
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
        runCatching {
            val root = json.parseToJsonElement(body)
            val parsed = jsonParsingUtils.parseTracksFromResponse(root)
            if (parsed.isNotEmpty()) return parsed
        }
        return runCatching {
            val trackResponse: dev.brahmkshatriya.echo.extension.models.DabTrackResponse = json.decodeFromString(body)
            trackResponse.tracks.map { converter.toTrack(it) }
        }.getOrElse { emptyList() }
    }

    /** Public accessors (limit/offset ignored by API) */
    @Suppress("unused")
    fun getFavorites(limit: Int = 200, offset: Int = 0): List<Track> {
        val all = loadAllFavorites()
        if (offset >= all.size) return emptyList()
        val slice = if (limit <= 0) all.drop(offset) else all.drop(offset).take(limit)
        return slice
    }

    @Suppress("unused")
    fun getFavoritesAuthenticated(limit: Int = 200, offset: Int = 0): List<Track> = getFavorites(limit, offset)
}

package dev.brahmkshatriya.echo.extension.dabapi

import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.Converter
import dev.brahmkshatriya.echo.extension.utils.RequestUtils
import dev.brahmkshatriya.echo.extension.utils.JsonParsingUtils
import dev.brahmkshatriya.echo.extension.utils.ApiConstants

class FavoritesService(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val settings: Settings,
    private val converter: Converter
) {
    companion object {
        private const val FAVORITES_PATH = "favorites"
    }

    private val requestUtils: RequestUtils by lazy { RequestUtils(settings) }
            private val jsonParsingUtils: JsonParsingUtils by lazy { JsonParsingUtils(json, converter) }

    /** Always fetch the full favorites list fresh from server */
    private fun fetchFavorites(): List<Track> {
        if (!requestUtils.isLoggedIn()) return emptyList()
        return try {
            val url = ApiConstants.api(FAVORITES_PATH)
            val req = requestUtils.newRequestBuilder(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    requestUtils.clearSessionOnAuthFailure(resp.code)
                    return emptyList()
                }
                val body = resp.body?.string() ?: return emptyList()
                // Parse with consolidated util (supports keys: favorites, tracks, etc.)
                val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
                if (root != null) {
                    val parsed = jsonParsingUtils.parseTracksFromResponse(root)
                    if (parsed.isNotEmpty()) return parsed
                }
                // Fallback to DabTrackResponse structure
                runCatching {
                    val trackResponse: dev.brahmkshatriya.echo.extension.models.DabTrackResponse = json.decodeFromString(body)
                    trackResponse.tracks.map { converter.toTrack(it) }
                }.getOrElse { emptyList() }
            }
        } catch (_: Throwable) { emptyList() }
    }

    fun getFavorites(limit: Int = 200, offset: Int = 0): List<Track> {
        val all = fetchFavorites()
        if (offset >= all.size) return emptyList()
        return if (limit <= 0) all.drop(offset) else all.drop(offset).take(limit)
    }
}

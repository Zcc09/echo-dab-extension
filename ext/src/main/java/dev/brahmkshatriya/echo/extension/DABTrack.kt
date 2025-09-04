package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.json.JsonObject

/**
 * Handles track-related API calls.
 */
class DABTrack(private val api: DABApi) {
    /**
     * Get details for a specific track.
     * Corresponds to: `GET /track/{trackId}`
     */
    suspend fun getTrack(trackId: String): JsonObject {
        return api.callApi(path = "/track/$trackId")
    }

    /**
     * Get lyrics for a specific track.
     * Corresponds to: `GET /track/{trackId}/lyrics`
     */
    suspend fun getLyrics(trackId: String): JsonObject {
        return api.callApi(path = "/track/$trackId/lyrics")
    }
}

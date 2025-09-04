package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.json.JsonObject

/**
 * Handles artist-related API calls.
 */
class DABArtist(private val api: DABApi) {
    /**
     * Get details for a specific artist.
     * Corresponds to: `GET /artist/{artistId}`
     */
    suspend fun getArtist(artistId: String): JsonObject {
        return api.callApi(path = "/artist/$artistId")
    }

    /**
     * Get an artist's top tracks.
     * Corresponds to: `GET /artist/{artistId}/top-tracks`
     */
    suspend fun getArtistTopTracks(artistId: String): JsonObject {
        return api.callApi(path = "/artist/$artistId/top-tracks")
    }
}

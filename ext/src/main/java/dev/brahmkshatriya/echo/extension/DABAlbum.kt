package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.json.JsonObject

/**
 * Handles album-related API calls.
 */
class DABAlbum(private val api: DABApi) {
    /**
     * Get details for a specific album.
     * Corresponds to: `GET /album/{albumId}`
     */
    suspend fun getAlbum(albumId: String): JsonObject {
        return api.callApi(path = "/album/$albumId")
    }
}

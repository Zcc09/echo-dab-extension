package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.json.JsonObject

/**
 * Handles playlist-related API calls.
 */
class DABPlaylist(private val api: DABApi) {
    /**
     * Get details for a specific playlist.
     * Corresponds to: `GET /playlist/{playlistId}`
     */
    suspend fun getPlaylist(playlistId: String): JsonObject {
        return api.callApi(path = "/playlist/$playlistId")
    }

    /**
     * Get a user's public playlists.
     * Corresponds to: `GET /user/{userId}/playlists`
     */
    suspend fun getUserPlaylists(userId: DABSession): JsonObject {
        return api.callApi(path = "/user/$userId/playlists")
    }
}

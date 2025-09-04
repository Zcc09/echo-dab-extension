package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser.toTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DABPlaylistClient(
    private val api: DABApi,
) : PlaylistClient {

    override val source: String = "DAB"

    override suspend fun getPlaylist(playlist: Playlist, page: Int): Page<Track>? {
        val playlistId = playlist.id
        val response = api.callApi(
            path = "/libraries/$playlistId",
            queryParams = mapOf("page" to page.toString())
        )

        val libraryJson = response["library"]?.jsonObject ?: return null
        val tracksJson = libraryJson["tracks"] as? JsonArray ?: return null
        val paginationJson = libraryJson["pagination"]?.jsonObject ?: return null

        val tracks = tracksJson.mapNotNull { it.jsonObject.toTrack() }
        val hasMore = paginationJson["hasMore"]?.jsonPrimitive?.content?.toBoolean() ?: false

        return Page(tracks, hasMore)
    }
}
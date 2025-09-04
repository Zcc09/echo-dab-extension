package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser.toTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean

class DABPlaylistClient(
    private val api: DABApi,
) : PlaylistClient {

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        return playlist
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        return PagedData.SingleSource { page ->
            val playlistId = playlist.id.removePrefix("playlist:")
            val response = api.callApi(
                path = "/libraries/$playlistId",
                queryParams = mapOf("page" to (page + 1).toString()) // API is 1-indexed
            )

            val libraryJson = response["library"]?.jsonObject ?: return@SingleSource Feed.Empty
            val tracksJson = libraryJson["tracks"] as? JsonArray ?: return@SingleSource Feed.Empty
            val paginationJson = libraryJson["pagination"]?.jsonObject

            val tracks = tracksJson.mapNotNull { it.jsonObject.toTrack() }
            val hasMore = paginationJson?.get("hasMore")?.jsonPrimitive?.boolean ?: false

            Feed(
                data = tracks,
                nextPage = if (hasMore) page + 1 else null
            )
        }.toFeed()
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? {
        return null
    }
}
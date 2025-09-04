package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser.toTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

class DABAlbumClient(
    private val api: DABApi,
) : AlbumClient {

    // This method should return the detailed album information.
    // For simplicity, we'll return the input album as the API provides all data at once.
    override suspend fun loadAlbum(album: Album): Album {
        return album
    }

    // This method now handles fetching the tracks for the album.
    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val albumId = album.id.removePrefix("album:")
        val response = api.callApi(path = "/album/$albumId")
        val albumJson = response["album"]?.jsonObject ?: return null
        val tracksJson = albumJson["tracks"] as? JsonArray ?: return null
        val tracks = tracksJson.mapNotNull { it.jsonObject.toTrack() }
        return Feed(tracks, emptyList())
    }

    // This method is for more complex album pages with shelves. We don't need it.
    override suspend fun loadFeed(album: Album): Feed<dev.brahmkshatriya.echo.common.models.Shelf>? {
        return null
    }
}
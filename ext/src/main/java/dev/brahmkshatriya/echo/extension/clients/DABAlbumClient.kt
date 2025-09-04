package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser.toTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

class DABAlbumClient(
    private val api: DABApi,
) : AlbumClient {

    override suspend fun loadAlbum(album: Album): Album {
        return album
    }

    override suspend fun loadTracks(album: Album): Feed<Track> {
        val albumId = album.id.removePrefix("album:")
        val response = api.callApi(path = "/album/$albumId")
        val albumJson = response["album"]?.jsonObject ?: return Feed.Empty
        val tracksJson = albumJson["tracks"] as? JsonArray ?: return Feed.Empty
        val tracks = tracksJson.mapNotNull { it.jsonObject.toTrack() }
        return Feed(tracks)
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        return null
    }
}

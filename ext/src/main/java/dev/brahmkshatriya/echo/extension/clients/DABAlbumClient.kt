package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser.toTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

class DABAlbumClient(
    private val api: DABApi,
) : AlbumClient {

    override val source: String = "DAB"

    override suspend fun getAlbum(album: Album.Small, page: Int): Page<Track>? {
        // API does not support pagination for album tracks, so we ignore the `page` parameter.
        if (page > 1) return Page(emptyList(), false)

        val response = api.callApi(path = "/album/${album.id}")
        val albumJson = response["album"]?.jsonObject ?: return null
        val tracksJson = albumJson["tracks"] as? JsonArray ?: return null

        val tracks = tracksJson.mapNotNull { it.jsonObject.toTrack() }
        return Page(tracks, false)
    }
}
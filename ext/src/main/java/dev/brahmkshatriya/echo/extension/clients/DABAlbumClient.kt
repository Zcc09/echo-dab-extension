package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DABApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.brahmkshatriya.echo.extension.DABParser.toTrack // Import the parser function

class DABAlbumClient(private val api: DABApi) : AlbumClient {
    override val source: String = "DAB"
    override suspend fun getAlbum(album: Album.Small, page: Int): Page<Track>? {
        val response = api.callApi(path = "/album/${album.id}")
        val albumJson = response["album"]?.jsonObject ?: return null
        val tracksJson = albumJson["tracks"] as? JsonArray ?: return null
        // Use the centralized parser
        val tracks = tracksJson.mapNotNull { it.jsonObject.toTrack() }
        return Page(tracks, false)
    }
}

    override val source: String = "DAB"

    override suspend fun getAlbum(album: Album.Small, page: Int): Page<Track>? {
        // The API doesn't support pagination for album tracks, so we ignore the 'page' parameter.
        val response = api.callApi(path = "/album/${album.id}")
        val albumJson = response["album"]?.jsonObject ?: return null
        val tracksJson = albumJson["tracks"] as? JsonArray ?: return null

        val tracks = tracksJson.mapNotNull { it.jsonObject.toTrack(album) }

        // Since there is no pagination, we return all tracks on the first page and indicate no more pages.
        return Page(tracks, false)
    }

    // Helper to parse a Track from the Album endpoint's JSON response.
    private fun JsonObject.toTrack(album: Album.Small): Track? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val title = this["title"]?.jsonPrimitive?.content ?: return null
        val duration = this["duration"]?.jsonPrimitive?.int?.toLong()

        val artistName = this["artist"]?.jsonPrimitive?.content
        val artistId = this["artistId"]?.jsonPrimitive?.content
        val artists = if (artistName != null && artistId != null) {
            listOf(Artist.Small(artistId, artistName, null, "DAB"))
        } else {
            emptyList()
        }

        return Track(
            source = "DAB",
            id = id,
            title = title,
            artists = artists,
            album = album,
            cover = album.cover, // Reuse the album's cover
            duration = duration,
            streamable = true
        )
    }
}
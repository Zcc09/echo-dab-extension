package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DABApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.brahmkshatriya.echo.extension.DABParser.toTrack // Import the parser function

class DABPlaylistClient(private val api: DABApi) : PlaylistClient {
    override val source: String = "DAB"
    override suspend fun getPlaylist(playlist: Playlist, page: Int): Page<Track>? {
        val response = api.callApi(path = "/libraries/${playlist.id}", queryParams = mapOf("page" to page.toString()))
        val libraryJson = response["library"]?.jsonObject ?: return null
        val tracksJson = libraryJson["tracks"] as? JsonArray ?: return null
        val paginationJson = libraryJson["pagination"]?.jsonObject ?: return null
        // Use the centralized parser
        val tracks = tracksJson.mapNotNull { it.jsonObject.toTrack() }
        val hasMore = paginationJson["hasMore"]?.jsonPrimitive?.content?.toBoolean() ?: false
        return Page(tracks, hasMore)
    }
}

    // Helper function to parse a Track JSON object into an Echo Track model.
    // We will centralize this in a Parser class later.
    private fun JsonObject.toTrack(): Track? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val title = this["title"]?.jsonPrimitive?.content ?: return null
        val cover = this["albumCover"]?.jsonPrimitive?.content
        val duration = this["duration"]?.jsonPrimitive?.int?.toLong()

        val artistName = this["artist"]?.jsonPrimitive?.content
        val artistId = this["artistId"]?.jsonPrimitive?.content
        val artists = if (artistName != null && artistId != null) {
            listOf(Artist.Small(artistId, artistName, null, "DAB"))
        } else {
            emptyList()
        }

        val albumTitle = this["albumTitle"]?.jsonPrimitive?.content
        val albumId = this["albumId"]?.jsonPrimitive?.content
        val album = if (albumTitle != null && albumId != null) {
            Album.Small(albumId, albumTitle, null, "DAB")
        } else {
            null
        }

        return Track(
            source = "DAB",
            id = id,
            title = title,
            artists = artists,
            album = album,
            cover = cover,
            duration = duration,
            streamable = true // Assuming all tracks from the API are streamable
        )
    }
}
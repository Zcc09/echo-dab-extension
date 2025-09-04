package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DABApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.brahmkshatriya.echo.extension.DABParser.toAlbum // Import parser functions
import dev.brahmkshatriya.echo.extension.DABParser.toTrack

class DABArtistClient(private val api: DABApi) : ArtistClient {
    override val source: String = "DAB"
    override suspend fun getArtist(artist: Artist.Small): Feed {
        val response = api.callApi(path = "/discography", queryParams = mapOf("artistId" to artist.id))
        val artistName = response["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: artist.name
        val albumsJson = response["albums"] as? JsonArray ?: JsonArray(emptyList())
        // Use the centralized parser
        val albums = albumsJson.mapNotNull { it.jsonObject.toAlbum() }
        val shelves = listOf(Shelf.Grid("$artistName's Albums", albums, null))
        return Feed(shelves)
    }

    override suspend fun getTopTracks(artist: Artist.Small, page: Int): Page<Track>? {
        if (page > 1) return Page(emptyList(), false)
        val response = api.callApi(path = "/discography", queryParams = mapOf("artistId" to artist.id))
        val albumsJson = response["albums"] as? JsonArray ?: return null
        // Use the centralized parser
        val topTracks = albumsJson
            .flatMap { it.jsonObject["tracks"] as? JsonArray ?: emptyList() }
            .mapNotNull { it.jsonObject.toTrack() }
            .take(20)
        return Page(topTracks, false)
    }
}

    // We'll move these parser helpers to a dedicated file later.
    private fun JsonObject.toAlbum(): Album.Small? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val title = this["title"]?.jsonPrimitive?.content ?: return null
        val cover = this["cover"]?.jsonPrimitive?.content
        return Album.Small(id, title, cover, "DAB")
    }

    private fun JsonObject.toAlbumWithTracks(): Album? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val title = this["title"]?.jsonPrimitive?.content ?: return null
        val cover = this["cover"]?.jsonPrimitive?.content
        val tracksJson = this["tracks"] as? JsonArray ?: return null
        val tracks = tracksJson.mapNotNull { it.jsonObject.toTrack(cover) }
        return Album(id, title, cover, "DAB", null, tracks)
    }

    private fun JsonObject.toTrack(albumCover: String?): Track? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val title = this["title"]?.jsonPrimitive?.content ?: return null
        val artistName = this["artist"]?.jsonPrimitive?.content
        val artistId = this["artistId"]?.jsonPrimitive?.content
        val artists = if (artistName != null && artistId != null) {
            listOf(Artist.Small(artistId, artistName, null, "DAB"))
        } else emptyList()

        return Track(
            source = "DAB",
            id = id,
            title = title,
            artists = artists,
            album = null,
            cover = albumCover,
            duration = this["duration"]?.jsonPrimitive?.int?.toLong(),
            streamable = true
        )
    }
}
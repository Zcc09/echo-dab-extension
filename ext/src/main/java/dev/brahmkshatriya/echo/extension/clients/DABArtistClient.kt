package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser.toAlbum
import dev.brahmkshatriya.echo.extension.DABParser.toTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DABArtistClient(
    private val api: DABApi,
) : ArtistClient {

    override suspend fun loadArtist(artist: Artist): Artist {
        return artist
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val artistId = artist.id.removePrefix("artist:")
        val response = api.callApi(
            path = "/discography",
            queryParams = mapOf("artistId" to artistId)
        )

        val artistName = response["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: artist.name
        val albumsJson = response["albums"] as? JsonArray ?: JsonArray(emptyList())

        val albums = albumsJson.mapNotNull { it.jsonObject.toAlbum() }
        val albumShelf = Shelf.Grid("$artistName's Albums", albums)

        val topTracks = albumsJson
            .take(10)
            .flatMap { album ->
                val tracksJson = album.jsonObject["tracks"] as? JsonArray ?: emptyList()
                tracksJson.take(2)
            }
            .mapNotNull { it.jsonObject.toTrack() }
        val topTracksShelf = Shelf.Lists("Top Tracks", topTracks)

        return Feed(listOf(topTracksShelf, albumShelf))
    }
}

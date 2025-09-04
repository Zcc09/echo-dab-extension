package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser.toAlbum
import dev.brahmkshatriya.echo.extension.DABParser.toTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

class DABArtistClient(
    private val api: DABApi,
) : ArtistClient {

    override val source: String = "DAB"

    override suspend fun getArtist(artist: Artist.Small): Feed {
        val response = api.callApi(
            path = "/discography",
            queryParams = mapOf("artistId" to artist.id)
        )

        val artistName = response["artist"]?.jsonObject?.get("name")?.toString()?.trim('"') ?: artist.name
        val albumsJson = response["albums"] as? JsonArray ?: JsonArray(emptyList())
        val albums = albumsJson.mapNotNull { it.jsonObject.toAlbum() }

        val shelves = listOf(
            Shelf.Grid("$artistName's Albums", albums)
        )
        return Feed(shelves)
    }

    override suspend fun getTopTracks(artist: Artist.Small, page: Int): Page<Track>? {
        if (page > 1) return Page(emptyList(), false)

        val response = api.callApi(
            path = "/discography",
            queryParams = mapOf("artistId" to artist.id)
        )

        val albumsJson = response["albums"] as? JsonArray ?: return null
        val topTracks = albumsJson
            .take(10) // Take the first 10 albums
            .flatMap { album ->
                val tracksJson = album.jsonObject["tracks"] as? JsonArray ?: emptyList()
                tracksJson.take(2) // Take the first 2 tracks from each album
            }
            .mapNotNull { it.jsonObject.toTrack() }

        return Page(topTracks, false)
    }
}
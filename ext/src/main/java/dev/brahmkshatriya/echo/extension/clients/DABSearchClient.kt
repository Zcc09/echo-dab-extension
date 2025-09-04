package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser.toAlbum
import dev.brahmkshatriya.echo.extension.DABParser.toArtist
import dev.brahmkshatriya.echo.extension.DABParser.toTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

class DABSearchClient(
    private val api: DABApi,
) : SearchFeedClient {

    // Rename `getSearchFeed` to `loadSearchFeed`.
    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        return supervisorScope {
            val tracksDeferred = async {
                api.callApi(path = "/search", queryParams = mapOf("q" to query, "type" to "track"))
            }
            val albumsDeferred = async {
                api.callApi(path = "/search", queryParams = mapOf("q" to query, "type" to "album"))
            }
            val artistsDeferred = async {
                api.callApi(path = "/search", queryParams = mapOf("q" to query, "type" to "artist"))
            }

            val shelves = mutableListOf<Shelf>()

            try {
                val tracksJson = tracksDeferred.await()["results"] as? JsonArray
                if (!tracksJson.isNullOrEmpty()) {
                    val tracks = tracksJson.mapNotNull { it.jsonObject.toTrack() }
                    shelves.add(Shelf.Lists("Tracks", tracks))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                val albumsJson = albumsDeferred.await()["results"] as? JsonArray
                if (!albumsJson.isNullOrEmpty()) {
                    val albums = albumsJson.mapNotNull { it.jsonObject.toAlbum() }
                    shelves.add(Shelf.Grid("Albums", albums))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                val artistsJson = artistsDeferred.await()["results"] as? JsonArray
                if (!artistsJson.isNullOrEmpty()) {
                    val artists = artistsJson.mapNotNull { it.jsonObject.toArtist() }
                    shelves.add(Shelf.Grid("Artists", artists))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Return a Feed object with the shelves.
            Feed(shelves, emptyList())
        }
    }
}
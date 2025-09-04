package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser.toPlaylist
import dev.brahmkshatriya.echo.extension.DABParser.toTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

class DABLibraryClient(
    private val api: DABApi,
) : LibraryFeedClient {

    override val source: String = "DAB"

    override suspend fun getFeed(): Feed = supervisorScope {
        // Fetch playlists and favorites in parallel.
        val playlistsDeferred = async { api.callApi(path = "/libraries") }
        val favoritesDeferred = async { api.callApi(path = "/favorites") }

        val shelves = mutableListOf<Shelf>()

        // Process playlists
        try {
            val playlistsJson = playlistsDeferred.await()["libraries"] as? JsonArray
            if (!playlistsJson.isNullOrEmpty()) {
                val playlists = playlistsJson.mapNotNull { it.jsonObject.toPlaylist() }
                shelves.add(Shelf.Grid("Playlists", playlists))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Process favorites
        try {
            val favoritesJson = favoritesDeferred.await()["favorites"] as? JsonArray
            if (!favoritesJson.isNullOrEmpty()) {
                val tracks = favoritesJson.mapNotNull { it.jsonObject.toTrack() }
                shelves.add(Shelf.Lists("Favorite Tracks", tracks))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@supervisorScope Feed(shelves)
    }
}
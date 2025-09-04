package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.extension.DABParser.toLastFmArtist
import dev.brahmkshatriya.echo.extension.DABParser.toLastFmTrack
import dev.brahmkshatriya.echo.extension.LastFmApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DABHomefeedClient(
    private val lastFmApi: LastFmApi
) : HomeFeedClient {

    // Rename `getFeed` to `loadHomeFeed` and update the return type.
    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val response = lastFmApi.getHomeFeed()
        val sections = response["sections"] as? JsonArray ?: return Feed.Empty

        val shelves = sections.mapNotNull { section ->
            val sectionJson = section.jsonObject
            val title = sectionJson["title"]?.jsonPrimitive?.content ?: "Untitled"
            val itemsJson = sectionJson["items"] as? JsonArray ?: return@mapNotNull null
            val type = sectionJson["type"]?.jsonPrimitive?.content

            when (type) {
                "lastfm:track" -> {
                    val tracks = itemsJson.mapNotNull { it.jsonObject.toLastFmTrack() }
                    // The old Shelf.Lists constructor takes the title and items.
                    Shelf.Lists(title, tracks)
                }
                "lastfm:artist" -> {
                    val artists = itemsJson.mapNotNull { it.jsonObject.toLastFmArtist() }
                    // The old Shelf.Grid constructor takes the title and items.
                    Shelf.Grid(title, artists)
                }
                else -> null
            }
        }
        // The old Feed constructor takes a list of shelves and tabs.
        return Feed(shelves, emptyList())
    }
}
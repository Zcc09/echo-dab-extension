package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.extension.LastFmApi
import dev.brahmkshatriya.echo.extension.DABParser.toLastFmArtist
import dev.brahmkshatriya.echo.extension.DABParser.toLastFmTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

class DABHomefeedClient(
    private val lastFmApi: LastFmApi
) : HomeFeedClient {

    override val source: String = "DAB"

    override suspend fun getFeed(): Feed {
        val response = lastFmApi.getHomeFeed()
        val sections = response["sections"] as? JsonArray ?: return Feed(emptyList())

        val shelves = sections.mapNotNull { section ->
            val sectionJson = section.jsonObject
            val title = sectionJson["title"]?.toString()?.trim('"') ?: "Untitled"
            val itemsJson = sectionJson["items"] as? JsonArray ?: return@mapNotNull null
            val type = sectionJson["type"]?.toString()?.trim('"')

            when (type) {
                "lastfm:track" -> {
                    val tracks = itemsJson.mapNotNull { it.jsonObject.toLastFmTrack() }
                    Shelf.Lists(title, tracks)
                }
                "lastfm:artist" -> {
                    val artists = itemsJson.mapNotNull { it.jsonObject.toLastFmArtist() }
                    Shelf.Grid(title, artists)
                }
                else -> null
            }
        }
        return Feed(shelves)
    }
}
package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser
import kotlinx.serialization.json.jsonArray

/**
 * Handles the business logic for fetching artist-related data.
 * It coordinates calls to the DABApi and uses the DABParser to format the responses
 * into UI-ready components like Shelves.
 *
 * @param api The authenticated DABApi client for making network calls.
 * @param parser The DABParser for converting JSON into application models.
 */
class DABArtistClient(private val api: DABApi, private val parser: DABParser) {

    /**
     * Loads the full details for a specific artist from the API.
     *
     * @param artist A partial Artist object, usually containing just the ID.
     * @return A full Artist object with all details populated.
     */
    suspend fun loadArtist(artist: Artist): Artist {
        val artistJson = api.getArtist(artist.id)
        return parser.run { artistJson.toArtist() }
    }

    /**
     * Constructs the content shelves for an artist's page (e.g., Top Tracks, Albums).
     * This function makes individual API calls for each section of the page.
     *
     * @param artist The artist for whom to build the page.
     * @return A Feed of Shelf objects to be displayed in the UI.
     */
    fun getShelves(artist: Artist): Feed<Shelf> = PagedData.Single {
        // This list will hold the shelves we build for the artist's page.
        val shelves = mutableListOf<Shelf>()

        // 1. Create a "Top Tracks" shelf.
        // We call the dedicated endpoint for an artist's top tracks.
        val topTracksJson = api.getArtistTopTracks(artist.id)
        val topTracksArray = topTracksJson["tracks"]?.jsonArray
        if (topTracksArray != null && topTracksArray.isNotEmpty()) {
            // Use the parser to create a shelf from the list of tracks.
            parser.run {
                topTracksArray.toShelfItemsList(name = "Top Tracks", typeHint = "track")
            }?.let { shelves.add(it) }
        }

        // 2. Create an "Albums" shelf.
        // We fetch the main artist data, assuming it contains their albums.
        val artistJson = api.getArtist(artist.id)
        val albumsArray = artistJson["albums"]?.jsonArray
        if (albumsArray != null && albumsArray.isNotEmpty()) {
            parser.run {
                albumsArray.toShelfItemsList(name = "Albums", typeHint = "album")
            }?.let { shelves.add(it) }
        }

        // Future shelves (e.g., "Related Artists") could be added here with more API calls.
        // For example:
        // val relatedArtistsJson = api.getRelatedArtists(artist.id)
        // ... create shelf ...

        shelves
    }.toFeed()


    /**
     * A simple helper to get the follower count from the artist's extras map.
     * The parser is responsible for putting the data there.
     *
     * @param artist The artist object.
     * @return The number of followers, or null if not available.
     */
    fun getFollowersCount(artist: Artist): Long? {
        return artist.extras["followers"]?.toLongOrNull()
    }
}

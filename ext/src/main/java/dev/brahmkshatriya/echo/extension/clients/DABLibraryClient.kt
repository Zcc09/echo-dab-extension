package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser
import dev.brahmkshatriya.echo.extension.DABSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/**
 * Handles the business logic for fetching the user's personal library content.
 *
 * @param api The authenticated DABApi client for making network calls.
 * @param parser The DABParser for converting JSON into application models.
 * @param userId The ID of the current logged-in user, required to fetch their playlists.
 */
class DABLibraryClient(
    private val api: DABApi,
    private val parser: DABParser,
    private val userId: DABSession
) {

    // A static list of tabs for the library screen, adapted to the API structure.
    // "Favorites" are tracks, and "Libraries" are user-created playlists.
    private val tabs = listOf(
        Tab("all", "All"),
        Tab("favorites", "Favorites"), // For tracks
        Tab("libraries", "Libraries")  // For playlists
    )

    /**
     * Loads the main, tabbed feed for the library.
     */
    fun loadLibraryFeed(): Feed<Shelf> {
        return Feed(tabs) { selectedTab ->
            when (selectedTab?.id) {
                // For the "All" tab, fetch everything in parallel.
                "all" -> loadLibraryFeedAll()
                // For specific tabs, fetch only that category.
                "favorites" -> fetchCategory("tracks", "track", "Favorites") { api.getFavorites() }
                "libraries" -> fetchCategory("playlists", "playlist", "Libraries") { api.getUserPlaylists(userId) }
                else -> emptyList()
            }.toFeedData()
        }
    }

    /**
     * Fetches all library categories concurrently for a fast initial load.
     * This is used for the "All" tab.
     */
    private suspend fun loadLibraryFeedAll(): List<Shelf> {
        // supervisorScope ensures that if one network call fails, the others can still complete.
        return supervisorScope {
            // Create a list of concurrent jobs to fetch all library content.
            val jobs = listOf(
                async(Dispatchers.IO) { fetchCategory("tracks", "track", "Favorites") { api.getFavorites() } },
                async(Dispatchers.IO) { fetchCategory("playlists", "playlist", "Libraries") { api.getUserPlaylists(userId) } }
            )
            // Wait for all jobs to finish and flatten the results into a single list of shelves.
            jobs.flatMap { it.await() }
        }
    }

    /**
     * A generic helper function to fetch and parse a single category of library items.
     *
     * @param jsonKey The key to find the data array in the JSON response (e.g., "tracks").
     * @param typeHint A hint for the parser (e.g., "track").
     * @param shelfTitle The title to display for the created shelf.
     * @param apiCall The suspend lambda that makes the actual network request.
     * @return A list containing a single Shelf for the category.
     */
    private suspend fun fetchCategory(
        jsonKey: String,
        typeHint: String,
        shelfTitle: String,
        apiCall: suspend () -> JsonObject
    ): List<Shelf> {
        return try {
            val jsonObject = apiCall()
            // Assume the response JSON has a key (e.g., "tracks") that contains the array of items.
            val dataArray = jsonObject[jsonKey]?.jsonArray
            val shelf = parser.run { dataArray?.toShelfItemsList(shelfTitle, typeHint) }
            // Return a list containing the single shelf, or an empty list if parsing failed.
            listOfNotNull(shelf)
        } catch (e: Exception) {
            // Gracefully handle network or parsing errors by returning an empty list.
            e.printStackTrace()
            emptyList()
        }
    }
}


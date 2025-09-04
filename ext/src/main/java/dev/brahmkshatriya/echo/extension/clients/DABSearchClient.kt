package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.QuickSearchItem // Ensure this is correctly imported
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Handles the business logic for search functionality.
 * It provides methods for both quick search suggestions and full, tabbed search results.
 *
 * NOTE: The "Browse" or "Discover" functionality from the original Deezer client has been
 * removed, as it is not a search-related feature. This logic should be placed in a
 * separate client (e.g., a `DABHomeClient`).
 *
 * @param api The authenticated DABApi client for making network calls.
 * @param parser The DABParser for converting JSON into application models.
 */
class DABSearchClient(private val api: DABApi, private val parser: DABParser) {

    // A simple in-memory cache for the last search result to avoid re-fetching
    // data when the user switches between tabs (e.g., from "All" to "Artists").
    @Volatile
    private var lastSearchResult: Pair<String, List<Shelf>>? = null

    /**
     * Provides quick search suggestions as the user types.
     * This fetches full search results but only parses the top items to display as suggestions.
     *
     * @param query The user's search query.
     * @return A list of `QuickSearchItem`s to display in a dropdown.
     */
    suspend fun quickSearch(query: String): List<QuickSearchItem> {
        if (query.isBlank()) return emptyList()

        return try {
            val searchJson = api.search(query)
            val suggestions = mutableListOf<QuickSearchItem>()

            // Add top track result, if available
            searchJson["tracks"]?.jsonArray?.firstOrNull()?.let {
                parser.run { it.jsonObject.toTrack() }
                    // FIXED: Changed QuickSearchItem.Result to QuickSearchItem.Media, passing 'track' and 'searched = false'
                    .let { track -> suggestions.add(QuickSearchItem.Media(track, searched = false)) }
            }
            // Add top artist result, if available
            searchJson["artists"]?.jsonArray?.firstOrNull()?.let {
                parser.run { it.jsonObject.toArtist() }
                    // FIXED: Changed QuickSearchItem.Result to QuickSearchItem.Media, passing 'artist' and 'searched = false'
                    .let { artist -> suggestions.add(QuickSearchItem.Media(artist, searched = false)) }
            }
            // Add top album result, if available
            searchJson["albums"]?.jsonArray?.firstOrNull()?.let {
                parser.run { it.jsonObject.toAlbum() }
                    // FIXED: Changed QuickSearchItem.Result to QuickSearchItem.Media, passing 'album' and 'searched = false'
                    .let { album -> suggestions.add(QuickSearchItem.Media(album, searched = false)) }
            }

            suggestions
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Loads a full, tabbed feed of search results for a given query.
     *
     * @param query The user's search query.
     * @return A Feed object containing tabs and shelves of results.
     */
    suspend fun loadSearchFeed(query: String): Feed<Shelf> { // FIXED: Added 'suspend' keyword
        if (query.isBlank()) {
            // Return an empty feed if the query is blank. Browse functionality is handled elsewhere.
            return PagedData.Single<Shelf> { emptyList() }.toFeed()
        }

        // The Feed constructor takes a list of tabs and a lambda to load content for a selected tab.
        return Feed(tabs = loadSearchTabs(query)) { selectedTab ->
            val shelves = lastSearchResult?.second ?: emptyList()

            when (selectedTab?.id) {
                "ALL" -> shelves.toFeedData()
                else -> {
                    // Filter the cached shelves to show only the one matching the selected tab.
                    shelves.filter { it.id == selectedTab?.id }.toFeedData()
                }
            }
        }
    }

    /**
     * A helper function that performs the search, caches the result, and generates the tabs.
     * This is called by the Feed to set up the tab strip.
     */
    private suspend fun loadSearchTabs(query: String): List<Tab> {
        // If the query is the same as the last one, return the cached tabs to avoid a network call.
        if (lastSearchResult?.first == query) {
            return generateTabsFromShelves(lastSearchResult?.second ?: emptyList())
        }

        val searchJson = api.search(query)
        val shelves = mutableListOf<Shelf>()

        // Process each category from the search result and create a corresponding shelf.
        parser.run {
            searchJson["tracks"]?.jsonArray?.toShelfItemsList("Tracks", "track")?.let { shelves.add(it) }
            searchJson["albums"]?.jsonArray?.toShelfItemsList("Albums", "album")?.let { shelves.add(it) }
            searchJson["artists"]?.jsonArray?.toShelfItemsList("Artists", "artist")?.let { shelves.add(it) }
            // Playlists can be added here if the API supports it.
            // searchJson["playlists"]?.jsonArray?.toShelfItemsList("Playlists", "playlist")?.let { shelves.add(it) }
        }

        // Cache the fully parsed result.
        lastSearchResult = query to shelves

        return generateTabsFromShelves(shelves)
    }

    /**
     * Generates a list of Tab objects from a list of shelves.
     */
    private fun generateTabsFromShelves(shelves: List<Shelf>): List<Tab> {
        if (shelves.isEmpty()) return emptyList()
        // The first tab is always "All".
        val tabs = mutableListOf(Tab("ALL", "All"))
        // Create a tab for each shelf (e.g., a "Tracks" tab for the "Tracks" shelf).
        shelves.forEach { shelf ->
            tabs.add(Tab(shelf.id, shelf.title))
        }
        return tabs
    }
}
package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Shelf // Assuming Shelf is the base type
// If 'Shelf.Lists.Items' is a specific subtype frequently returned by your parser,
// and it's visible, you might technically need to import it, but `as Shelf` handles the cast.
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handles the business logic for fetching the Home/Discovery feed.
 *
 * @param api The authenticated DABApi client for making network calls.
 * @param parser The parser to convert JSON responses into structured data models.
 */
class DABHomeFeedClient(private val api: DABApi, private val parser: DABParser) {

    /**
     * Loads the main home feed.
     * It fetches a list of sections from the API and processes them in parallel to create shelves.
     */
    // FIX 1: Add 'suspend' modifier as it calls a suspend function (api.getHomeFeed())
    suspend fun loadHomeFeed(): Feed<Shelf> = PagedData.Single {
        // Fetch the raw JSON for the home feed from the API.
        val homeJson = api.getHomeFeed()
        val sectionsArray = homeJson["sections"]?.jsonArray

        // If there are no sections, return an empty list immediately.
        if (sectionsArray.isNullOrEmpty()) {
            return@Single emptyList()
        }

        // Use a supervisorScope to run all section parsing in parallel.
        // If one section fails to parse, it won't cancel the others.
        supervisorScope {
            sectionsArray.map { sectionElement ->
                async(Dispatchers.Default) {
                    val sectionJson = sectionElement.jsonObject
                    val title = sectionJson["title"]?.jsonPrimitive?.content ?: "Untitled Shelf"
                    val items = sectionJson["items"]?.jsonArray

                    // Determine the type of items in the shelf for the parser.
                    // A well-designed API would include a `type` field in the section,
                    // or we can infer it from the first item.
                    val typeHint = sectionJson["type"]?.jsonPrimitive?.content
                        ?: items?.firstOrNull()?.jsonObject?.get("type")?.jsonPrimitive?.content
                        ?: "track" // Default to track if no type is found

                    // Use the parser to convert the JSON array into a Shelf object.
                    // FIX 2: Explicitly cast the result of toShelfItemsList to Shelf.
                    // This assumes toShelfItemsList returns a subtype of Shelf (e.g., Shelf.Lists.Items)
                    // and that the cast is always safe at runtime. Using 'as Shelf?' ensures null-safety.
                    with(parser) {
                        items?.toShelfItemsList(name = title, typeHint = typeHint) as Shelf?
                    }
                }
            }.mapNotNull { it.await() } // Wait for all parallel jobs and filter out any null results.
        }
    }.toFeed()
}
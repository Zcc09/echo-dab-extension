package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DABApi
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Handles the business logic for fetching and parsing lyrics for a track.
 *
 * @param api The authenticated DABApi client for making network calls.
 */
class DABLyricsClient(private val api: DABApi) {

    /**
     * Fetches the lyrics for a given track from the DAB API.
     * It automatically handles both timed (synchronized) and simple (plain text) lyrics.
     *
     * @param track The track for which to find lyrics.
     * @return A Feed containing a single `Lyrics` object if found, otherwise an empty feed.
     */
    fun searchTrackLyrics(track: Track): Feed<Lyrics> = PagedData.Single {
        try {
            // 1. Make the API call to the dedicated lyrics endpoint.
            val lyricsJson = api.getLyrics(track.id)

            // 2. Safely extract the main lyrics object from the response.
            // We assume the API returns a root object with a "lyrics" key.
            val lyricsObject = lyricsJson["lyrics"]?.jsonObject
                ?: return@Single emptyList() // No lyrics found.

            // 3. Determine if the lyrics are timed or simple.
            // The type of this variable must match what the final `Lyrics` constructor expects.
            // Change Any? to Lyrics.Lyric?
            val parsedLyricsData: Lyrics.Lyric? = if (lyricsObject.containsKey("lines")) {
                // Case 1: Timed Lyrics are available.
                val linesArray = lyricsObject["lines"]!!.jsonArray
                val timedLines = linesArray.mapNotNull { lineElement ->
                    val lineObj = lineElement.jsonObject
                    val line = lineObj["words"]?.jsonPrimitive?.content
                    val start = lineObj["startTimeMs"]?.jsonPrimitive?.long
                    // ASSUMPTION: The API provides a duration for each line.
                    val duration = lineObj["durationMs"]?.jsonPrimitive?.long

                    if (line != null && start != null && duration != null) {
                        // Call the constructor correctly with all 3 arguments.
                        Lyrics.Item(line, start, start + duration)
                    } else {
                        null
                    }
                }
                Lyrics.Timed(timedLines)
            } else if (lyricsObject.containsKey("text")) {
                // Case 2: Only simple, plain text lyrics are available.
                val lyricsText = lyricsObject["text"]!!.jsonPrimitive.content
                Lyrics.Simple(lyricsText)
            } else {
                // Case 3: The lyrics object is empty or has an unknown format.
                null
            }

            // 4. If we successfully parsed lyrics, wrap them in the final object.
            if (parsedLyricsData != null) {
                listOf(
                    Lyrics(
                        id = track.id, // Use the track ID as a stable identifier.
                        title = track.title,
                        lyrics = parsedLyricsData // Now the type matches Lyrics.Lyric?
                    )
                )
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            // If any error occurs (network, parsing, etc.), return an empty list gracefully.
            e.printStackTrace()
            emptyList()
        }
    }.toFeed()
}
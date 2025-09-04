package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.AudioStreamProvider
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABSession
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import kotlinx.serialization.json.intOrNull

/**
 * Handles the business logic for fetching track-specific data, like streamable URLs.
 * It now also handles sorting the audio qualities based on user preference.
 *
 * @param api The authenticated DABApi client for making network calls.
 * @param session The DABSession, used to access user settings like preferred quality.
 */
class DABTrackClient(private val api: DABApi, private val session: DABSession) {

    private val client by lazy { OkHttpClient() }

    /**
     * Fetches the track details from the API and populates the `streamables` list.
     * The list of streamables will be sorted according to the user's quality preference.
     */
    suspend fun loadTrack(track: Track): Track {
        val trackJson = api.getTrack(track.id)
        val streamsJsonArray = trackJson["streamables"]?.jsonArray
            ?: return track.copy(streamables = emptyList())

        val allStreamables = streamsJsonArray.mapNotNull { streamElement ->
            val streamJson = streamElement.jsonObject
            val url = streamJson["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val qualityTitle = streamJson["quality"]?.jsonPrimitive?.content ?: "Unknown"
            // We use the bitrate as the quality indicator. A higher number is better.
            val qualityValue = streamJson["bitrate"]?.jsonPrimitive?.intOrNull ?: 0

            Streamable.server(
                id = url,
                title = qualityTitle,
                quality = qualityValue,
            )
        }

        // Get the user's preferred quality from settings, default to "best".
        val preferredQuality = session.settings?.getString("audio_quality") ?: "best"

        // Sort all available streams from the highest bitrate to the lowest.
        val sortedByBest = allStreamables.sortedByDescending { it.quality }

        // Re-order the list to prioritize the user's preferred quality,
        // ensuring fallbacks are still available for the player to try.
        val prioritizedStreamables = when (preferredQuality) {
            "high" -> {
                // Prioritize streams at or below 320kbps, then list the rest.
                val highAndLower = sortedByBest.filter { it.quality <= 320 }
                val higher = sortedByBest.filter { it.quality > 320 }
                highAndLower + higher
            }
            "standard" -> {
                // Prioritize streams at or below 128kbps, then list the rest.
                val standardAndLower = sortedByBest.filter { it.quality <= 128 }
                val higher = sortedByBest.filter { it.quality > 128 }
                standardAndLower + higher
            }
            else -> { // "best"
                // For "best", we don't need to re-order, just use the descending sort.
                sortedByBest
            }
        }

        return track.copy(streamables = prioritizedStreamables)
    }

    /**
     * Given a Streamable, this prepares the actual audio media for the player.
     * For the DAB API, this involves opening a direct HTTP stream to the URL.
     */
    suspend fun loadStreamable(streamable: Streamable): Streamable.Media {
        val contentLength = AudioStreamProvider.getContentLength(streamable.id, client)
        return Streamable.InputProvider { start, _ ->
            Pair(
                AudioStreamProvider.openStream(streamable, client, start),
                contentLength - start
            )
        }.toSource(id = streamable.id).toMedia()
    }
}


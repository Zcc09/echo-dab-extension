package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Handles the business logic for fetching album-related data.
 * It coordinates calls to the DABApi and uses the DABParser to format the responses.
 *
 * @param api The authenticated DABApi client for making network calls.
 * @param parser The DABParser for converting JSON into application models.
 */
class DABAlbumClient(private val api: DABApi, private val parser: DABParser) {

    /**
     * Loads the full details for a specific album from the API.
     * This is typically called when a user navigates to an album page.
     *
     * @param album A partial Album object, usually containing just the ID.
     * @return A full Album object with all details populated.
     */
    suspend fun loadAlbum(album: Album): Album {
        // The API call is now simpler, using the album's ID directly.
        val albumJson = api.getAlbum(album.id)
        // The parser takes the entire JSON response and converts it.
        return parser.run { albumJson.toAlbum() }
    }

    /**
     * Loads the feed of tracks for a given album.
     * This also enriches each track with the ID of the *next* track in the album,
     * which is a useful feature for building a playback queue.
     *
     * @param album The album for which to load tracks.
     * @return A Feed object containing the list of tracks.
     */
    fun loadTracks(album: Album): Feed<Track> = PagedData.Single {
        // Fetch the album data, which should include the list of tracks.
        val albumJson = api.getAlbum(album.id)

        // Safely extract the array of tracks from the JSON response.
        val tracksArray = albumJson["tracks"]?.jsonArray
            ?: return@Single emptyList() // Return an empty list if no tracks are found.

        // We use mapIndexed to get both the track and its position in the list.
        tracksArray.mapIndexed { index, trackJson ->
            val currentTrack = parser.run { trackJson.jsonObject.toTrack() }

            // Look ahead in the array to find the next track.
            val nextTrack = tracksArray.getOrNull(index + 1)?.let {
                parser.run { it.jsonObject.toTrack() }
            }

            // Create a copy of the current track, adding the next track's ID to its extras.
            // This allows the music player to know what's coming up next.
            currentTrack.copy(
                extras = currentTrack.extras + mapOf(
                    "NEXT" to nextTrack?.id.orEmpty(),
                    "album_id" to album.id
                )
            )
        }
    }.toFeed()
}

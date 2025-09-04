package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Handles the business logic for fetching playlist-related data.
 * It coordinates calls to the DABApi and uses the DABParser to format the responses.
 *
 * @param api The authenticated DABApi client for making network calls.
 * @param parser The DABParser for converting JSON into application models.
 */
class DABPlaylistClient(private val api: DABApi, private val parser: DABParser) {

    /**
     * Loads the full details for a specific playlist from the API.
     *
     * @param playlist A partial Playlist object, usually containing just the ID.
     * @return A full Playlist object with all details populated.
     */
    suspend fun loadPlaylist(playlist: Playlist): Playlist {
        // Fetch the raw JSON for the playlist using its ID.
        val playlistJson = api.getPlaylist(playlist.id)
        // Let the parser handle the conversion to a structured Playlist object.
        return parser.run { playlistJson.toPlaylist() }
    }

    /**
     * Loads the feed of tracks for a given playlist.
     * This logic is intentionally similar to `DABAlbumClient.loadTracks` for consistency.
     * It also enriches each track with the ID of the *next* track.
     *
     * @param playlist The playlist for which to load tracks.
     * @return A Feed object containing the list of tracks.
     */
    fun loadTracks(playlist: Playlist): Feed<Track> = PagedData.Single {
        // Fetch the playlist data, which should include the list of tracks.
        val playlistJson = api.getPlaylist(playlist.id)

        // Safely extract the array of tracks from the JSON response.
        val tracksArray = playlistJson["tracks"]?.jsonArray
            ?: return@Single emptyList() // Return an empty list if no tracks are found.

        // Use mapIndexed to get both the track and its position in the list.
        tracksArray.mapIndexed { index, trackJson ->
            val currentTrack = parser.run { trackJson.jsonObject.toTrack() }

            // Look ahead in the array to find the next track.
            val nextTrack = tracksArray.getOrNull(index + 1)?.let {
                parser.run { it.jsonObject.toTrack() }
            }

            // Create a copy of the current track, adding the next track's ID
            // and the playlist ID to its extras map.
            currentTrack.copy(
                extras = currentTrack.extras + mapOf(
                    "NEXT" to nextTrack?.id.orEmpty(),
                    "playlist_id" to playlist.id
                )
            )
        }
    }.toFeed()
}

package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.DABApi
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

class DABLyricsClient(private val api: DABApi) : LyricsClient {

    // This is the primary method for finding lyrics.
    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        val artistName = track.artists.firstOrNull()?.name ?: return Feed.Empty
        val trackTitle = track.title

        return try {
            val response = api.callApi(
                path = "/lyrics",
                queryParams = mapOf("artist" to artistName, "title" to trackTitle)
            )

            val lyricsText = response["lyrics"]?.jsonPrimitive?.content
            val isUnsynced = response["unsynced"]?.jsonPrimitive?.booleanOrNull ?: true

            if (lyricsText != null) {
                val lyrics = Lyrics(lyricsText, isUnsynced, null, null, clientId)
                Feed(listOf(lyrics), emptyList())
            } else {
                Feed.Empty
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Feed.Empty
        }
    }

    // This method is for when the user selects a specific lyrics result.
    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        return lyrics
    }

    // We don't have any settings for lyrics.
    override suspend fun getSettingItems(): List<Setting> {
        return emptyList()
    }

    override fun setSettings(settings: Settings) {
        // Not needed
    }
}
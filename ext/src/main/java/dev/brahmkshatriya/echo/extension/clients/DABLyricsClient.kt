package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DABApi
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DABLyricsClient(private val api: DABApi) : LyricsClient {

    override val source: String = "DAB"

    override suspend fun getLyrics(track: Track): Lyrics? {
        val artistName = track.artists.firstOrNull()?.name ?: return null
        val trackTitle = track.title

        return try {
            val response = api.callApi(
                path = "/lyrics",
                queryParams = mapOf(
                    "artist" to artistName,
                    "title" to trackTitle
                )
            )

            val lyricsText = response["lyrics"]?.jsonPrimitive?.content
            val isUnsynced = response["unsynced"]?.jsonObject?.get("boolean")?.boolean

            if (lyricsText != null) {
                Lyrics(lyricsText, isUnsynced ?: true)
            } else {
                null
            }
        } catch (e: Exception) {
            // If there's any error fetching lyrics, just return null.
            e.printStackTrace()
            null
        }
    }
}
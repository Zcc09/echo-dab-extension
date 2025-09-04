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
        // The API requires artist and title, which we can get from the track object.
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
        } catch (e: DABApi.DABApiException) {
            // The API returns 404 if lyrics are not found, which is not a critical error.
            if (e.code == 404) {
                null
            } else {
                throw e
            }
        }
    }
}
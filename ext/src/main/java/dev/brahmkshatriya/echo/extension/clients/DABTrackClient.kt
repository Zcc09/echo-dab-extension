package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DABApi
import kotlinx.serialization.json.jsonPrimitive

class DABTrackClient(private val api: DABApi) : TrackClient {

    override suspend fun loadTrack(track: Track): Track {
        return track
    }

    override suspend fun loadStreamable(track: Track): Streamable? {
        return try {
            val response = api.callApi(
                path = "/stream",
                queryParams = mapOf("trackId" to track.id)
            )
            val streamUrl = response["streamUrl"]?.jsonPrimitive?.content ?: return null

            // Get the cookie header string from our API class.
            val cookieHeader = api.getCookies(streamUrl)
            val headers = if (cookieHeader.isNotEmpty()) mapOf("Cookie" to cookieHeader) else null

            // Create the EchoMediaItem with the URL and the authentication cookie.
            val mediaItem = EchoMediaItem(
                url = streamUrl,
                quality = "Standard",
                headers = headers
            )

            // Return a Streamable.Media object.
            Streamable.Media(listOf(mediaItem))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.extension.DABApi
import kotlinx.serialization.json.jsonPrimitive

class DABTrackClient(private val api: DABApi) : TrackClient {

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        return track
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val response = api.callApi(
            path = "/stream",
            queryParams = mapOf("trackId" to streamable.id)
        )
        val streamUrl = response["streamUrl"]?.jsonPrimitive?.content
            ?: throw Exception("No stream URL found")

        val cookieHeader = api.getCookies(streamUrl)
        val headers = if (cookieHeader.isNotEmpty()) mapOf("Cookie" to cookieHeader) else emptyMap()

        return Streamable.Media.create(
            url = streamUrl,
            headers = headers
        )
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        return null
    }
}
package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DABApi

class DABTrackClient(private val api: DABApi) : TrackClient {

    override val source: String = "DAB"

    override suspend fun getStreamable(track: Track): Streamable? {
        // Get the InputStream directly from the API
        val inputStream = api.getAudioStream(track.id)
        // Return it as a Streamable.Stream
        return Streamable.Stream(inputStream)
    }
}
// In your file: C:/Users/Zcc09-Desktop/Desktop/Projects/Coding/echo-dab-extension/ext/src/main/java/dev/brahmkshatriya/echo/extension/AudioStreamProvider.kt
package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Streamable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import okio.source
import java.io.InputStream
import kotlin.math.max

object AudioStreamProvider {

    // You likely have an openStream function already, e.g.:
    suspend fun openStream(streamable: Streamable, client: OkHttpClient, startByte: Long): InputStream {
        val request = Request.Builder()
            .url(streamable.id)
            .header("Range", "bytes=$startByte-")
            .build()

        val response: Response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("Failed to open stream: ${response.code} ${response.message}")
        }
        return response.body?.byteStream()
            ?: throw Exception("No body in stream response")
    }


    /**
     * Fetches the content length of an audio stream using a HEAD request.
     *
     * @param url The URL of the audio stream.
     * @param client The OkHttpClient instance to use for the network call.
     * @return The content length in bytes, or 0L if unavailable or an error occurs.
     */
    suspend fun getContentLength(url: String, client: OkHttpClient): Long {
        return try {
            val request = Request.Builder()
                .head() // Use HEAD request to get headers without downloading the body
                .url(url)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val contentLength = response.header("Content-Length")?.toLongOrNull()
                    // Return 0L if Content-Length is missing or cannot be parsed,
                    // as it indicates an unknown length for streaming purposes.
                    max(0L, contentLength ?: 0L)
                } else {
                    println("Failed to get content length for $url: ${response.code} ${response.message}")
                    0L // Indicate unknown length or error
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            0L // Indicate unknown length or error
        }
    }
}
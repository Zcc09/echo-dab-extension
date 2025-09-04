package dev.brahmkshatriya.echo.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.InputStream

/**
 * Handles all network interactions with the Last.fm API.
 * @param apiKey The Last.fm API key provided by the user.
 */
class LastFmApi(private val apiKey: String) {

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json {
            isLenient = true
            ignoreUnknownKeys = true
            useArrayPolymorphism = true
        }
        private const val LASTFM_BASE_URL = "http://ws.audioscrobbler.com/2.0/"
    }

    private val client: OkHttpClient by lazy { OkHttpClient() }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun decodeJsonStream(stream: InputStream): JsonObject =
        withContext(Dispatchers.Default) {
            json.decodeFromStream(stream)
        }

    private suspend fun callLastFmApi(queryParams: Map<String, String>): JsonObject =
        withContext(Dispatchers.IO) {
            // If the user hasn't provided an API key, return an empty object to avoid errors.
            if (apiKey.isBlank()) {
                return@withContext JsonObject(emptyMap())
            }

            val urlBuilder = LASTFM_BASE_URL.toHttpUrl().newBuilder()

            urlBuilder.addQueryParameter("api_key", apiKey)
            urlBuilder.addQueryParameter("format", "
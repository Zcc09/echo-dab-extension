package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private suspend fun decodeJsonStream(stream: InputStream): JsonObject = withContext(Dispatchers.Default) {
        json.decodeFromStream(stream)
    }

    private suspend fun callLastFmApi(queryParams: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        // If the user hasn't provided an API key, return an empty object to avoid errors.
        if (apiKey.isBlank()) {
            return@withContext JsonObject(emptyMap())
        }

        val urlBuilder = LASTFM_BASE_URL.toHttpUrl().newBuilder()

        urlBuilder.addQueryParameter("api_key", apiKey)
        urlBuilder.addQueryParameter("format", "json")
        queryParams.forEach { (key, value) ->
            urlBuilder.addQueryParameter(key, value)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build()

        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Last.fm API call failed with status ${response.code}")
            }
            return@withContext response.body.source().use { decodeJsonStream(it.inputStream()) }
        }
    }

    suspend fun getHomeFeed(): JsonObject = supervisorScope {
        val topTracksDeferred = async {
            callLastFmApi(mapOf("method" to "chart.gettoptracks", "limit" to "20"))
        }
        val topArtistsDeferred = async {
            callLastFmApi(mapOf("method" to "chart.gettopartists", "limit" to "15"))
        }
        val topRockDeferred = async {
            callLastFmApi(mapOf("method" to "tag.gettoptracks", "tag" to "rock", "limit" to "15"))
        }
        val topPopDeferred = async {
            callLastFmApi(mapOf("method" to "tag.gettoptracks", "tag" to "pop", "limit" to "15"))
        }

        val sections = buildJsonArray {
            try {
                val topTracks = topTracksDeferred.await()["tracks"]?.jsonObject?.get("track")
                if (topTracks != null) {
                    add(buildJsonObject {
                        put("title", "Trending Worldwide")
                        put("type", "lastfm:track")
                        put("items", topTracks)
                    })
                }
            } catch (e: Exception) {
                println("Failed to load top tracks: ${e.message}")
            }

            try {
                val topArtists = topArtistsDeferred.await()["artists"]?.jsonObject?.get("artist")
                if (topArtists != null) {
                    add(buildJsonObject {
                        put("title", "Top Artists")
                        put("type", "lastfm:artist")
                        put("items", topArtists)
                    })
                }
            } catch (e: Exception) {
                println("Failed to load top artists: ${e.message}")
            }

            try {
                val topRock = topRockDeferred.await()["tracks"]?.jsonObject?.get("track")
                if (topRock != null) {
                    add(buildJsonObject {
                        put("title", "Top in Rock")
                        put("type", "lastfm:track")
                        put("items", topRock)
                    })
                }
            } catch (e: Exception) {
                println("Failed to load top rock tracks: ${e.message}")
            }

            try {
                val topPop = topPopDeferred.await()["tracks"]?.jsonObject?.get("track")
                if (topPop != null) {
                    add(buildJsonObject {
                        put("title", "Pop Essentials")
                        put("type", "lastfm:track")
                        put("items", topPop)
                    })
                }
            } catch (e: Exception) {
                println("Failed to load top pop tracks: ${e.message}")
            }
        }

        return@supervisorScope buildJsonObject {
            put("sections", sections)
        }
    }
}
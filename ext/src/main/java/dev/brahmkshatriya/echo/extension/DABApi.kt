package dev.brahmkshatriya.echo.extension


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import java.io.FilterInputStream
import java.io.InputStream
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody


/**
 * Handles all network interactions with the DAB API.
 * This class acts as a gateway and delegates calls to feature-specific handler classes.
 * It requires a valid DABSession to be initialized.
 *
 * @param session The session object containing the user's credentials.
 */
class DABApi(private val session: DABSession) {

    companion object {
        private val json = Json {
            isLenient = true
            ignoreUnknownKeys = true
            useArrayPolymorphism = true
        }
        private const val BASE_URL = "https://dab.yeet.su/api"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Custom exception for DAB API errors.
     */
    class DABApiException(val code: Int, message: String) : Exception("API Error $code: $message")

    // CookieJar to handle session cookies for authentication.
    private val cookieJar = object : CookieJar {
        private val cookieStore = mutableMapOf<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .build()
    }

    /**
     * A generic, internal function to make calls to the primary DAB API.
     */
    internal suspend fun callApi(
        path: String,
        method: String = "GET",
        queryParams: Map<String, String>? = null,
        bodyBuilder: (JsonObjectBuilder.() -> Unit)? = null
    ): JsonObject = withContext(Dispatchers.IO) {

        val urlBuilder = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments(path.trimStart('/'))

        queryParams?.forEach { (key, value) ->
            urlBuilder.addQueryParameter(key, value)
        }

        val requestBody: RequestBody? = bodyBuilder?.let {
            encodeJson(it).toRequestBody(JSON_MEDIA_TYPE)
        }

        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            // Removed Authorization header, authentication is now handled by the CookieJar.
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")

        when (method.uppercase()) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(requestBody ?: "".toRequestBody(null))
            "DELETE" -> requestBuilder.delete() // Corrected for DELETE with query params
            "PUT" -> requestBuilder.put(requestBody ?: "".toRequestBody(null))
            else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
        }

        client.newCall(requestBuilder.build()).await().use { response ->
            if (response.body.contentLength() == 0L) {
                if (response.isSuccessful) return@withContext JsonObject(emptyMap())
                else throw DABApiException(response.code, "API call failed with empty body")
            }

            val result = response.body.source().use { decodeJsonStream(it.inputStream()) }

            if (!response.isSuccessful) {
                val errorMessage = result["message"]?.jsonPrimitive?.content ?: "Unknown API error"
                throw DABApiException(response.code, errorMessage)
            }
            return@withContext result
        }
    }
    suspend fun getAudioStream(trackId: String): InputStream {
        // First, get the stream URL from our API
        val streamUrlResponse = callApi(
            path = "/stream",
            queryParams = mapOf("trackId" to trackId)
        )
        val streamUrl = streamUrlResponse["streamUrl"]?.jsonPrimitive?.content
            ?: throw Exception("Could not get stream URL")

        // Now, make a new request to that URL to get the raw audio data
        val request = Request.Builder().url(streamUrl).build()
        val response = withContext(Dispatchers.IO) {
            client.newCall(request).execute()
        }

        if (!response.isSuccessful) {
            response.close()
            throw Exception("Failed to fetch audio stream: ${response.code}")
        }

        // Return the input stream wrapped in a class that will close the response when done.
        return ResponseInputStream(response)
    }

    /**
     * An InputStream that closes the OkHttp Response when the stream is closed.
     * This is crucial for preventing resource leaks.
     */
    private class ResponseInputStream(private val response: Response) :
        FilterInputStream(response.body!!.byteStream()) {
        override fun close() {
            // Close the OkHttp response body, which also closes the underlying stream.
            response.close()
            super.close()
        }
    }

    //<============= Home Feed =============>
    suspend fun getHomeFeed(): JsonObject {
        val lastFmApi = LastFmApi()
        return lastFmApi.getHomeFeed()
    }


    //<============= User Features =============>
    suspend fun getMe(): JsonObject = callApi(path = "/auth/me")
    suspend fun getFavorites(): JsonObject = callApi(path = "/favorites")

    suspend fun addFavorite(track: JsonObject): JsonObject {
        return callApi(path = "/favorites", method = "POST") {
            put("track", track)
        }
    }

    suspend fun removeFavorite(trackId: String): JsonObject {
        return callApi(
            path = "/favorites",
            method = "DELETE",
            queryParams = mapOf("trackId" to trackId)
        )
    }


    //<============= Search =============>

    suspend fun search(query: String): JsonObject {
        return callApi(path = "/search", queryParams = mapOf("q" to query))
    }


    //<============= Playlists =============>

    suspend fun getPlaylist(playlistId: String): JsonObject {
        return callApi(path = "/libraries/$playlistId")
    }
    suspend fun getUserPlaylists(): JsonObject {
        return callApi(path = "/libraries")
    }

    //<============= JSON Helpers =============>

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun decodeJsonStream(stream: InputStream): JsonObject = withContext(Dispatchers.Default) {
        json.decodeFromStream(stream)
    }

    private suspend fun encodeJson(raw: JsonObjectBuilder.() -> Unit = {}): String = withContext(Dispatchers.IO) {
        json.encodeToString(buildJsonObject(raw))
    }
}


/**
 * Handles all network interactions with the Last.fm API.
 */
class LastFmApi {

    companion object {
        private val json = Json {
            isLenient = true
            ignoreUnknownKeys = true
            useArrayPolymorphism = true
        }
        private const val LASTFM_BASE_URL = "http://ws.audioscrobbler.com/2.0/"
        // IMPORTANT: Move this to your local.properties and access via BuildConfig
        private const val LASTFM_API_KEY = "YOUR_LASTFM_API_KEY"
    }

    private val client: OkHttpClient by lazy { OkHttpClient() }


    private suspend fun callLastFmApi(queryParams: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val urlBuilder = LASTFM_BASE_URL.toHttpUrl().newBuilder()

        urlBuilder.addQueryParameter("api_key", LASTFM_API_KEY)
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
            return@use response.body.source().use { json.decodeFromStream(it.inputStream()) }
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
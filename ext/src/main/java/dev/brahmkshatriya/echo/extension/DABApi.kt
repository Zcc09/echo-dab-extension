package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import kotlinx.coroutines.Dispatchers
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream

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
        private const val LASTFM_BASE_URL = "http://ws.audioscrobbler.com/2.0/"
        private const val LASTFM_API_KEY = "119e1fe4ac820c0e8a64fccdb8dbca98" // <-- IMPORTANT: Replace this
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Retrieves the auth token from the session.
     * Throws an IllegalStateException if the user is not logged in, preventing API calls.
     */
    private val token: String
        get() = session.credentials?.token
            ?: throw IllegalStateException("Cannot make API call: User is not logged in.")

    private val client: OkHttpClient by lazy { OkHttpClient() }

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
            .addHeader("Authorization", "Bearer $token") // Uses the token from the session
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")

        when (method.uppercase()) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(requestBody ?: "".toRequestBody(null))
            "DELETE" -> requestBuilder.delete(requestBody)
            "PUT" -> requestBuilder.put(requestBody ?: "".toRequestBody(null))
            else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
        }

        client.newCall(requestBuilder.build()).await().use { response ->
            if (response.body.contentLength() == 0L) {
                if (response.isSuccessful) return@withContext JsonObject(emptyMap())
                else throw Exception("API call failed with status ${response.code} and empty body")
            }

            val result = response.body.source().use { decodeJsonStream(it.inputStream()) }

            if (!response.isSuccessful) {
                val errorMessage = result["message"]?.jsonPrimitive?.content ?: "Unknown API error"
                throw Exception("API call failed with status ${response.code}: $errorMessage")
            }
            return@withContext result
        }
    }

    /**
     * A generic, internal function to make calls to the Last.fm API.
     */
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
            return@withContext response.body.source().use { decodeJsonStream(it.inputStream()) }
        }
    }


    //<============= Home Feed =============>

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

    //<============= User Features =============>
    suspend fun getMe(): JsonObject = callApi(path = "/me")
    suspend fun getFavorites(): JsonObject = callApi(path = "/favorites")
    suspend fun addFavorite(itemId: String, itemType: String): JsonObject {
        return callApi(path = "/favorites", method = "POST") {
            put("id", itemId) // Use "id" as per OpenAPI spec
            put("type", itemType) }
    }
    suspend fun removeFavorite(itemId: String, itemType: String): JsonObject {
        return callApi(path = "/favorites", method = "DELETE") {
            put("id", itemId) // Use "id" as per OpenAPI spec
            put("type", itemType)
        }
    }

    //<============= Search =============>

    suspend fun search(query: String): JsonObject {
        return callApi(path = "/search", queryParams = mapOf("q" to query))
    }

    //<============= Tracks =============>

    private val dabTrack by lazy { DABTrack(this) }
    suspend fun getTrack(trackId: String) = dabTrack.getTrack(trackId)
    suspend fun getLyrics(trackId: String) = dabTrack.getLyrics(trackId)

    //<============= Albums =============>

    private val dabAlbum by lazy { DABAlbum(this) }
    suspend fun getAlbum(albumId: String) = dabAlbum.getAlbum(albumId)

    //<============= Artists =============>

    private val dabArtist by lazy { DABArtist(this) }
    suspend fun getArtist(artistId: String) = dabArtist.getArtist(artistId)
    suspend fun getArtistTopTracks(artistId: String) = dabArtist.getArtistTopTracks(artistId)

    //<============= Playlists =============>

    private val dabPlaylist by lazy { DABPlaylist(this) }
    suspend fun getPlaylist(playlistId: String) = dabPlaylist.getPlaylist(playlistId)
    suspend fun getUserPlaylists(userId: DABSession) = dabPlaylist.getUserPlaylists(userId)

    //<============= JSON Helpers =============>

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun decodeJsonStream(stream: InputStream): JsonObject = withContext(Dispatchers.Default) {
        json.decodeFromStream(stream)
    }

    private suspend fun encodeJson(raw: JsonObjectBuilder.() -> Unit = {}): String = withContext(Dispatchers.IO) {
        json.encodeToString(buildJsonObject(raw))
    }
}
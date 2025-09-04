package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class DABApi(
    private val session: DABSession,
    private val client: OkHttpClient
) {

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        private const val BASE_URL = "https://dab.yeet.su/api"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    class DABApiException(val code: Int, message: String) : Exception("API Error $code: $message")

    fun getCookies(url: String): String {
        return client.cookieJar.loadForRequest(url.toHttpUrl())
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

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
            json.encodeToString(JsonObject.serializer(), buildJsonObject(it)).toRequestBody(JSON_MEDIA_TYPE)
        }

        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
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
            if (response.body?.contentLength() == 0L) {
                if (response.isSuccessful) return@withContext JsonObject(emptyMap())
                else throw DABApiException(response.code, "API call failed with empty body")
            }

            val result = response.body!!.source().use { decodeJsonStream(it.inputStream()) }

            if (!response.isSuccessful) {
                val errorMessage = result["message"]?.jsonPrimitive?.content ?: "Unknown API error"
                throw DABApiException(response.code, errorMessage)
            }
            return@withContext result
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun decodeJsonStream(stream: InputStream): JsonObject = withContext(Dispatchers.Default) {
        json.decodeFromStream(stream)
    }
}
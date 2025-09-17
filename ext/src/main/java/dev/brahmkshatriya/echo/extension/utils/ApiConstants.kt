package dev.brahmkshatriya.echo.extension.utils

object ApiConstants {
    const val BASE_URL = "https://dab.yeet.su/api"
    private const val STREAM_PATH = "/stream"

    /** Build full API URL from a path (path may start with or without '/') */
    fun api(path: String): String {
        return if (path.startsWith("/")) BASE_URL + path else BASE_URL + "/" + path
    }

    /** Build a stream URL for a track (optionally with quality param) WITHOUT encoding */
    fun streamUrl(trackId: String, quality: String? = null): String {
        val base = api("$STREAM_PATH?trackId=$trackId")
        return if (quality.isNullOrBlank()) base else "$base&quality=$quality"
    }
}

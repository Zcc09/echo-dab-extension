package dev.brahmkshatriya.echo.extension.dabapi

import kotlinx.serialization.json.Json
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.net.URL
import okhttp3.Request

class StreamResolver(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val settings: Settings
) {
    private val trackStreamCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val STREAM_CACHE_TTL_MS = 5 * 60 * 1000L

    /** Expose cache clear for logout */
    fun clearCache() {
        try { trackStreamCache.clear() } catch (_: Throwable) {}
        settings.putString("stream_cache_keys", null)
    }

    /** Load persistent stream cache from settings */
    fun loadPersistentStreamCache() {
        val keys = settings.getString("stream_cache_keys") ?: return
        if (keys.isBlank()) return
        val now = System.currentTimeMillis()
        val ids = keys.split(',').mapNotNull { it.trim().takeIf { s -> s.isNotBlank() } }
        val builder = mutableListOf<String>()
        for (id in ids) {
            val raw = settings.getString("stream_cache_$id") ?: continue
            val parts = raw.split('|', limit = 2)
            if (parts.isEmpty()) continue
            val ts = parts.getOrNull(0)?.toLongOrNull() ?: continue
            val url = parts.getOrNull(1) ?: continue
            if (now - ts <= STREAM_CACHE_TTL_MS) {
                try { trackStreamCache[id] = ts to url } catch (_: Throwable) {}
                builder.add(id)
            } else {
                settings.putString("stream_cache_$id", null)
            }
        }
        if (builder.isNotEmpty()) settings.putString("stream_cache_keys", builder.joinToString(","))
        else settings.putString("stream_cache_keys", null)
    }

    /** Persist stream cache entry to settings */
    private fun persistStreamCacheEntry(trackId: String, url: String) {
        try {
            val keyName = "stream_cache_$trackId"
            val value = "${System.currentTimeMillis()}|$url"
            settings.putString(keyName, value)

            val existingKeys = settings.getString("stream_cache_keys")
            val keysSet = existingKeys?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
            if (!keysSet.contains(trackId)) {
                keysSet.add(trackId)
                settings.putString("stream_cache_keys", keysSet.joinToString(","))
            }
        } catch (_: Throwable) {}
    }

    /** Normalize track ID for cache key */
    private fun normalizeTrackIdForCache(raw: String?): String? {
        if (raw == null) return null
        val s = raw.trim()
        if (s.isEmpty()) return null
        try {
            if (s.startsWith("http", true)) {
                val q = URL(s).query ?: return s
                val pairs = q.split('&')
                for (p in pairs) {
                    val kv = p.split('=')
                    if (kv.size >= 2 && kv[0].equals("trackId", true)) return kv[1]
                }
                return s
            }
        } catch (_: Throwable) {}
        return s
    }

    /** Get cached stream URL if available and valid */
    fun getCachedStreamUrl(trackId: String?): String? {
        val normalized = normalizeTrackIdForCache(trackId) ?: return null
        try {
            val entry = trackStreamCache[normalized] ?: return null
            if (System.currentTimeMillis() - entry.first <= STREAM_CACHE_TTL_MS) return entry.second
        } catch (_: Throwable) {}
        return null
    }

    /** Get stream URL for track */
    fun getStreamUrl(trackId: String): String? {
        if (trackId.isBlank()) return null
        val normalized = normalizeTrackIdForCache(trackId) ?: return null
        try {
            val entry = trackStreamCache[normalized]
            if (entry != null && System.currentTimeMillis() - entry.first <= STREAM_CACHE_TTL_MS) return entry.second
        } catch (_: Throwable) {}

        val url = if (normalized.startsWith("http", true)) normalized else "https://dab.yeet.su/api/stream?trackId=${URLEncoder.encode(normalized, "UTF-8") }"
        var result: String? = null

        try {
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                try {
                    val effective = resp.request.url.toString()
                    if (!effective.equals(url, ignoreCase = true) && effective.startsWith("http") && !effective.contains("/api/stream")) {
                        result = effective
                        try {
                            trackStreamCache[normalized] = System.currentTimeMillis() to effective
                            persistStreamCacheEntry(normalized, effective)
                        } catch (_: Throwable) {}
                        return@use
                    }
                } catch (_: Throwable) {}

                val loc = resp.header("Location")
                if (!loc.isNullOrBlank()) {
                    result = loc
                    try {
                        trackStreamCache[normalized] = System.currentTimeMillis() to loc
                        persistStreamCacheEntry(normalized, loc)
                    } catch (_: Throwable) {}
                    return@use
                }

                if (!resp.isSuccessful) return@use
                val body = resp.body?.string() ?: return@use

                try {
                    val sr = json.decodeFromString(dev.brahmkshatriya.echo.extension.models.DabStreamResponse.serializer(), body)
                    val resolved = sr.streamUrl ?: sr.url ?: sr.stream ?: sr.link
                    if (!resolved.isNullOrBlank()) {
                        result = resolved
                        try {
                            trackStreamCache[normalized] = System.currentTimeMillis() to resolved
                            persistStreamCacheEntry(normalized, resolved)
                        } catch (_: Throwable) {}
                        return@use
                    }
                } catch (_: Throwable) {}

                val s = body.trim()
                if (s.startsWith("http")) {
                    result = s
                    try {
                        trackStreamCache[normalized] = System.currentTimeMillis() to s
                        persistStreamCacheEntry(normalized, s)
                    } catch (_: Throwable) {}
                    return@use
                }
            }
        } catch (_: Throwable) { return null }

        if (!result.isNullOrBlank()) return result
        return null
    }
}
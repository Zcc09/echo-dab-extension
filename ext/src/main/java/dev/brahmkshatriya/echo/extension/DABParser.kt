package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Date as EchoDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class DABParser(private val session: DABSession) {

    fun JsonArray.toShelfItemsList(name: String, typeHint: String): Shelf.Lists.Items? {
        val items = this.mapNotNull { it.jsonObject.toEchoMediaItem(typeHint) }
        return if (items.isNotEmpty()) {
            Shelf.Lists.Items(
                id = name,
                title = name,
                list = items
            )
        } else {
            null
        }
    }

    private fun JsonObject.toEchoMediaItem(typeHint: String): EchoMediaItem? {
        // UPDATED: Now routes to new Last.fm parsers based on the typeHint
        return when (typeHint.lowercase()) {
            "track", "song" -> toTrack()
            "album" -> toAlbum()
            "artist" -> toArtist()
            "playlist" -> toPlaylist()
            "lastfm:track" -> toLastFmTrack()   // <- NEW
            "lastfm:artist" -> toLastFmArtist() // <- NEW
            else -> null
        }
    }

    // =================================================================
    // NEW: Functions for parsing Last.fm API data
    // =================================================================

    private fun JsonObject.toLastFmTrack(): Track? {
        val title = this["name"]?.jsonPrimitive?.content ?: return null
        val artistName = this["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "Unknown Artist"
        val artworkUrl = this["image"]?.jsonArray?.let { findBestImage(it) }

        // Last.fm charts don't provide stable IDs, so we create a temporary one.
        // This is fine for display, but it can't be used to fetch details later.
        val tempId = "lastfm:track:${artistName}:${title}"

        return Track(
            id = tempId,
            title = title,
            cover = artworkUrl?.toImageHolder(),
            artists = listOf(Artist(id = "lastfm:artist:$artistName", name = artistName)),
            // Fields not provided by Last.fm charts are left as null/empty.
            duration = null,
            releaseDate = null,
            album = null,
            streamables = emptyList(),
            isExplicit = false
        )
    }

    private fun JsonObject.toLastFmArtist(): Artist? {
        val name = this["name"]?.jsonPrimitive?.content ?: return null
        val artworkUrl = this["image"]?.jsonArray?.let { findBestImage(it) }
        val tempId = "lastfm:artist:${name}"

        return Artist(
            id = tempId,
            name = name,
            cover = artworkUrl?.toImageHolder(),
            bio = null
        )
    }

    private fun findBestImage(imageArray: JsonArray): String? {
        val imageUrls = imageArray.mapNotNull { it.jsonObject }.associate {
            it["size"]?.jsonPrimitive?.content to it["#text"]?.jsonPrimitive?.content
        }
        return imageUrls["extralarge"]
            ?: imageUrls["large"]
            ?: imageUrls["medium"]
            ?: imageUrls["small"]
            ?: imageUrls.values.firstOrNull { !it.isNullOrBlank() }
    }

    // =================================================================
    // ORIGINAL: Functions for parsing your primary API data remain untouched
    // =================================================================

    fun JsonObject.toTrack(): Track {
        // ... your original toTrack() code remains here, unchanged ...
        val albumJson = this["album"]?.jsonObject
        val artistsJson = this["artists"]?.jsonArray

        return Track(
            id = this["id"]?.jsonPrimitive?.content.orEmpty(),
            title = this["title"]?.jsonPrimitive?.content.orEmpty(),
            cover = this["coverArt"]?.jsonPrimitive?.content?.toImageHolder(),
            duration = this["duration_ms"]?.jsonPrimitive?.longOrNull,
            releaseDate = this["releaseDate"]?.jsonPrimitive?.content?.toDate(),
            artists = artistsJson?.map { it.jsonObject.toArtist() } ?: emptyList(),
            album = albumJson?.let {
                Album(
                    id = it["id"]?.jsonPrimitive?.content.orEmpty(),
                    title = it["title"]?.jsonPrimitive?.content.orEmpty(),
                    cover = it["coverArt"]?.jsonPrimitive?.content?.toImageHolder()
                )
            },
            streamables = this["streamUrl"]?.jsonPrimitive?.content?.let {
                listOf(Streamable.server(id = it, title = "Default", quality = 1))
            } ?: emptyList(),
            isExplicit = this["explicit"]?.jsonPrimitive?.content == "true"
        )
    }

    fun JsonObject.toAlbum(): Album {
        // ... your original toAlbum() code remains here, unchanged ...
        return Album(
            id = this["id"]?.jsonPrimitive?.content.orEmpty(),
            title = this["title"]?.jsonPrimitive?.content.orEmpty(),
            cover = this["coverArt"]?.jsonPrimitive?.content?.toImageHolder(),
            trackCount = this["trackCount"]?.jsonPrimitive?.longOrNull,
            artists = this["artists"]?.jsonArray?.map { it.jsonObject.toArtist() } ?: emptyList(),
            releaseDate = this["releaseDate"]?.jsonPrimitive?.content?.toDate(),
            description = this["description"]?.jsonPrimitive?.content.orEmpty()
        )
    }

    fun JsonObject.toArtist(): Artist {
        // ... your original toArtist() code remains here, unchanged ...
        return Artist(
            id = this["id"]?.jsonPrimitive?.content.orEmpty(),
            name = this["name"]?.jsonPrimitive?.content.orEmpty(),
            cover = this["picture"]?.jsonPrimitive?.content?.toImageHolder(),
            bio = this["bio"]?.jsonPrimitive?.content.orEmpty()
        )
    }

    fun JsonObject.toPlaylist(): Playlist {
        // ... your original toPlaylist() code remains here, unchanged ...
        val ownerJson = this["owner"]?.jsonObject
        return Playlist(
            id = this["id"]?.jsonPrimitive?.content.orEmpty(),
            title = this["name"]?.jsonPrimitive?.content.orEmpty(),
            cover = this["picture"]?.jsonPrimitive?.content?.toImageHolder(),
            description = this["description"]?.jsonPrimitive?.content.orEmpty(),
            trackCount = this["trackCount"]?.jsonPrimitive?.longOrNull,
            isEditable = ownerJson?.get("id")?.jsonPrimitive?.content == "LOGGED_IN_USER_ID_PLACEHOLDER"
        )
    }

    private fun String.toDate(): EchoDate? {
        // ... your original toDate() helper remains here, unchanged ...
        return try {
            val parts = this.split('-')
            if (parts.size == 3) {
                EchoDate(
                    year = parts[0].toInt(),
                    month = parts[1].toInt(),
                    day = parts[2].toInt()
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object DABParser {

    private const val SOURCE = "DAB"

    fun JsonObject.toTrack(): Track? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val title = this["title"]?.jsonPrimitive?.content ?: return null
        val coverUrl = this["albumCover"]?.jsonPrimitive?.content
        val duration = this["duration"]?.jsonPrimitive?.int?.toLong()

        val artistName = this["artist"]?.jsonPrimitive?.content
        val artists = if (artistName != null) {
            listOf(Artist(artistName, "artist:$artistName", null))
        } else {
            emptyList()
        }

        val albumTitle = this["albumTitle"]?.jsonPrimitive?.content
        val albumId = this["albumId"]?.jsonPrimitive?.content
        val album = if (albumTitle != null && albumId != null) {
            Album(albumTitle, "album:$albumId", null, null, null)
        } else {
            null
        }

        return Track(
            id = id,
            title = title,
            artists = artists,
            album = album,
            cover = coverUrl?.let { ImageHolder.Url(it) },
            duration = duration
        )
    }

    fun JsonObject.toAlbum(): Album? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val title = this["title"]?.jsonPrimitive?.content ?: return null
        val coverUrl = this["cover"]?.jsonPrimitive?.content
        val artistName = this["artist"]?.jsonPrimitive?.content
        val artist = if(artistName != null) Artist(artistName, "artist:$artistName", null) else null
        return Album(
            title = title,
            id = "album:$id",
            cover = coverUrl?.let { ImageHolder.Url(it) },
            artist = artist,
            releaseDate = null
        )
    }

    fun JsonObject.toArtist(): Artist? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val name = this["name"]?.jsonPrimitive?.content ?: return null
        val thumbnailUrl = this["image"]?.jsonPrimitive?.content
        return Artist(
            name = name,
            id = "artist:$id",
            thumbnail = thumbnailUrl?.let { ImageHolder.Url(it) }
        )
    }

    fun JsonObject.toPlaylist(): Playlist? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val name = this["name"]?.jsonPrimitive?.content ?: return null
        val trackCount = this["trackCount"]?.jsonPrimitive?.int
        return Playlist(
            name = name,
            id = "playlist:$id",
            cover = null,
            creator = null,
            trackCount = trackCount
        )
    }

    fun JsonObject.toUser(): User {
        val id = this["id"]?.jsonPrimitive?.int.toString()
        val username = this["username"]?.jsonPrimitive?.content ?: "Unknown"
        return User(id, username, null)
    }

    fun JsonObject.toLastFmTrack(): Track? {
        val title = this["name"]?.jsonPrimitive?.content ?: return null
        val artistJson = this["artist"]?.jsonObject
        val artistName = artistJson?.get("name")?.jsonPrimitive?.content ?: "Unknown"
        val coverUrl = (this["image"] as? JsonArray)
            ?.lastOrNull()?.jsonObject?.get("#text")?.jsonPrimitive?.content

        return Track(
            id = "lastfm_track:${artistName}_${title}",
            title = title,
            artists = listOf(Artist(artistName, "lastfm_artist:$artistName", null)),
            album = null,
            cover = coverUrl?.let { ImageHolder.Url(it) },
            duration = this["duration"]?.jsonPrimitive?.int?.toLong()
        ).apply { isStreamable = false }
    }

    fun JsonObject.toLastFmArtist(): Artist? {
        val name = this["name"]?.jsonPrimitive?.content ?: return null
        val coverUrl = (this["image"] as? JsonArray)
            ?.lastOrNull()?.jsonObject?.get("#text")?.jsonPrimitive?.content
        return Artist(
            name = name,
            id = "lastfm_artist:$name",
            thumbnail = coverUrl?.let { ImageHolder.Url(it) }
        )
    }
}
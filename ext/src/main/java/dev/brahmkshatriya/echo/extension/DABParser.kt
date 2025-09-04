package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object DABParser {

    fun JsonObject.toTrack(): Track? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val title = this["title"]?.jsonPrimitive?.content ?: return null
        val coverUrl = this["albumCover"]?.jsonPrimitive?.content
        val duration = this["duration"]?.jsonPrimitive?.int?.toLong()

        val artistName = this["artist"]?.jsonPrimitive?.content
        val artists = if (artistName != null) {
            listOf(Artist(artistName, "artist:$artistName"))
        } else {
            emptyList()
        }

        val albumTitle = this["albumTitle"]?.jsonPrimitive?.content
        val albumId = this["albumId"]?.jsonPrimitive?.content
        val album = if (albumTitle != null && albumId != null) {
            Album(
                id = "album:$albumId",
                title = albumTitle,
                cover = coverUrl?.toImageHolder(),
                artists = artists
            )
        } else {
            null
        }

        return Track(
            id = id,
            title = title,
            artists = artists,
            album = album,
            cover = coverUrl?.toImageHolder(),
            duration = duration,
            isPlayable = Track.Playable.Yes,
            streamables = listOf(Streamable.server(id, 0))
        )
    }

    fun JsonObject.toAlbum(): Album? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val title = this["title"]?.jsonPrimitive?.content ?: return null
        val coverUrl = this["cover"]?.jsonPrimitive?.content
        val artistName = this["artist"]?.jsonPrimitive?.content
        val artist = if (artistName != null) Artist(artistName, "artist:$artistName") else null
        return Album(
            id = "album:$id",
            title = title,
            cover = coverUrl?.toImageHolder(),
            artists = if (artist != null) listOf(artist) else emptyList(),
            releaseDate = null
        )
    }

    fun JsonObject.toArtist(): Artist? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val name = this["name"]?.jsonPrimitive?.content ?: return null
        val thumbnailUrl = this["image"]?.jsonPrimitive?.content
        return Artist(
            id = "artist:$id",
            name = name,
            cover = thumbnailUrl?.toImageHolder()
        )
    }

    fun JsonObject.toPlaylist(): Playlist? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val name = this["name"]?.jsonPrimitive?.content ?: return null
        val trackCount = this["trackCount"]?.jsonPrimitive?.int
        return Playlist(
            id = "playlist:$id",
            title = name,
            cover = null,
            trackCount = trackCount?.toLong(),
            isEditable = false
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
            artists = listOf(Artist(artistName, "lastfm_artist:$artistName")),
            album = null,
            cover = coverUrl?.toImageHolder(),
            duration = this["duration"]?.jsonPrimitive?.int?.toLong(),
            // FIX: Added the required 'reason' argument to the constructor.
            isPlayable = Track.Playable.No("Not streamable"),
            streamables = emptyList()
        )
    }

    fun JsonObject.toLastFmArtist(): Artist? {
        val name = this["name"]?.jsonPrimitive?.content ?: return null
        val coverUrl = (this["image"] as? JsonArray)
            ?.lastOrNull()?.jsonObject?.get("#text")?.jsonPrimitive?.content
        return Artist(
            id = "lastfm_artist:$name",
            name = name,
            cover = coverUrl?.toImageHolder()
        )
    }
}
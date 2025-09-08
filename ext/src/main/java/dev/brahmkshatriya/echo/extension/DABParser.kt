package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

object DABParser {

    fun JsonObject.toTrack(): Track? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val title = this["title"]?.jsonPrimitive?.content ?: return null
        val coverUrl = this["albumCover"]?.jsonPrimitive?.content
        val duration = this["duration"]?.jsonPrimitive?.int?.toLong()
        val artistName = this["artist"]?.jsonPrimitive?.content
        val artists = if (artistName != null) listOf(Artist(artistName, artistName)) else emptyList()
        val albumTitle = this["albumTitle"]?.jsonPrimitive?.content
        val album = if (albumTitle != null) Album(
            title = albumTitle,
            cover = coverUrl?.toImageHolder(),
            artists = artists
        ) else null

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
        val artist = if (artistName != null) Artist(artistName, artistName) else null
        return Album(
            id = id,
            title = title,
            cover = coverUrl?.toImageHolder(),
            artists = if (artist != null) listOf(artist) else emptyList()
        )
    }

    fun JsonObject.toArtist(): Artist? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val name = this["name"]?.jsonPrimitive?.content ?: return null
        val thumbnailUrl = this["image"]?.jsonPrimitive?.content
        return Artist(
            id = id,
            name = name,
            cover = thumbnailUrl?.toImageHolder()
        )
    }

    fun JsonObject.toPlaylist(): Playlist? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val name = this["name"]?.jsonPrimitive?.content ?: return null
        val trackCount = this["trackCount"]?.jsonPrimitive?.int
        return Playlist(
            id = id,
            title = name,
            trackCount = trackCount?.toLong(),
            isEditable = false
        )
    }

    fun JsonObject.toUser(): User {
        val id = this["id"]?.jsonPrimitive?.int.toString()
        val username = this["username"]?.jsonPrimitive?.content ?: "Unknown"
        return User(id, username)
    }
}
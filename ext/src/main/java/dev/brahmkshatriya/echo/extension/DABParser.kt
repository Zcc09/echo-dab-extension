package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object DABParser {

    private const val SOURCE = "DAB"

    fun JsonObject.toTrack(): Track? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val title = this["title"]?.jsonPrimitive?.content ?: return null
        val cover = this["albumCover"]?.jsonPrimitive?.content
        val duration = this["duration"]?.jsonPrimitive?.int?.toLong()

        val artistName = this["artist"]?.jsonPrimitive?.content
        val artistId = this["artistId"]?.jsonPrimitive?.content
        val artists = if (artistName != null) {
            listOf(Artist.Small(artistId ?: artistName, artistName, null, SOURCE))
        } else {
            emptyList()
        }

        val albumTitle = this["albumTitle"]?.jsonPrimitive?.content
        val albumId = this["albumId"]?.jsonPrimitive?.content
        val album = if (albumTitle != null) {
            Album.Small(albumId ?: albumTitle, albumTitle, cover, SOURCE)
        } else {
            null
        }

        return Track(
            source = SOURCE,
            id = id,
            title = title,
            artists = artists,
            album = album,
            cover = cover,
            duration = duration,
            streamable = true
        )
    }
    fun JsonObject.toLastFmTrack(): Track? {
        val title = this["name"]?.jsonPrimitive?.content ?: return null
        val artistName = this["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "Unknown"

        // Last.fm provides multiple image sizes in an array. We'll try to find the largest one.
        val cover = (this["image"] as? JsonArray)
            ?.lastOrNull()?.jsonObject?.get("#text")?.jsonPrimitive?.content

        return Track(
            source = SOURCE,
            id = "lastfm_track_${artistName}_${title}", // Create a unique-enough ID
            title = title,
            artists = listOf(Artist.Small("lastfm_artist_$artistName", artistName, null, SOURCE)),
            album = null,
            cover = cover,
            duration = this["duration"]?.jsonPrimitive?.int?.toLong(),
            streamable = false // Tracks from Last.fm are for metadata only
        )
    }

    fun JsonObject.toLastFmArtist(): Artist.Small? {
        val name = this["name"]?.jsonPrimitive?.content ?: return null
        val cover = (this["image"] as? JsonArray)
            ?.lastOrNull()?.jsonObject?.get("#text")?.jsonPrimitive?.content

        return Artist.Small(
            id = "lastfm_artist_$name", // Create a unique-enough ID
            name = name,
            thumbnail = cover,
            source = SOURCE
        )
    }
    fun JsonObject.toAlbum(): Album.Small? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val title = this["title"]?.jsonPrimitive?.content ?: return null
        val cover = this["cover"]?.jsonPrimitive?.content
        return Album.Small(id, title, cover, SOURCE)
    }

    fun JsonObject.toArtist(): Artist.Small? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val name = this["name"]?.jsonPrimitive?.content ?: return null
        val thumbnail = this["image"]?.jsonPrimitive?.content
        return Artist.Small(id, name, thumbnail, SOURCE)
    }

    fun JsonObject.toPlaylist(): Playlist.Small? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val name = this["name"]?.jsonPrimitive?.content ?: return null
        val trackCount = this["trackCount"]?.jsonPrimitive?.int
        return Playlist.Small(id, name, null, SOURCE, trackCount)
    }

    fun JsonObject.toUser(): User {
        val id = this["id"]?.jsonPrimitive?.int.toString()
        val username = this["username"]?.jsonPrimitive?.content ?: "Unknown"
        return User(id, username, null, SOURCE)
    }
}
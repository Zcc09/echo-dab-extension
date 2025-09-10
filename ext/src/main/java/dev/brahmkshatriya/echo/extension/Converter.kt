package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.models.DabPlaylist
import dev.brahmkshatriya.echo.extension.models.DabTrack
import dev.brahmkshatriya.echo.extension.models.DabUser

class Converter {
    fun toUser(user: DabUser): User {
        return User(
            id = user.id,
            name = user.attributes.name,
            cover = user.attributes.artwork?.toImageHolder()
        )
    }

    fun toPlaylist(playlist: DabPlaylist): Playlist {
        return Playlist(
            id = playlist.href,
            title = playlist.attributes.name,
            cover = playlist.attributes.artwork.toImageHolder(),
            authors = listOfNotNull(playlist.attributes.curatorName).map { Artist(it, it) },
            isEditable = false,
        )
    }

    fun toTrack(track: DabTrack): Track {
        return Track(
            id = track.id,
            title = track.attributes.name,
            cover = track.attributes.artwork.toImageHolder(),
            artists = listOf(Artist(id = track.attributes.artistName, name = track.attributes.artistName)),
            album = track.attributes.albumName?.let { Album(id = it, title = it) },
            streamables = listOf(
                Streamable.server(
                    id = "stream",
                    extras = mapOf("url" to track.attributes.url)
                )
            )
        )
    }
}
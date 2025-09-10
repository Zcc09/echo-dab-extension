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
            id = user.id.toString(),
            name = user.username,
            // The /auth/me endpoint doesn't seem to provide an avatar.
            cover = null
        )
    }

    fun toPlaylist(playlist: DabPlaylist): Playlist {
        return Playlist(
            id = playlist.id,
            title = playlist.name,
            // The API for playlists doesn't provide a cover image.
            cover = null,
            authors = listOf(Artist(id = "user", name = "You")),
            isEditable = false,
            trackCount = playlist.trackCount.toLong()
        )
    }

    fun toTrack(track: DabTrack): Track {
        val streamUrl = "https://dab.yeet.su/api/stream?trackId=${track.id}"

        return Track(
            id = track.id.toString(),
            title = track.title,
            cover = track.albumCover?.toImageHolder(),
            artists = listOf(Artist(id = track.artistId?.toString() ?: track.artist, name = track.artist)),
            album = track.albumTitle?.let {
                Album(
                    id = track.albumId ?: it,
                    title = it
                )
            },
            streamables = listOf(
                Streamable.server(
                    id = "stream",
                    quality = 0,
                    extras = mapOf(
                        "url" to streamUrl
                    )
                )
            ),
            duration = track.duration.toLong() * 1000
        )
    }
}
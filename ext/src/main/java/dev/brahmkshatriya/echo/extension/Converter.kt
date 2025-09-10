package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.extension.models.DabPlaylist
import dev.brahmkshatriya.echo.extension.models.DabTrack
import dev.brahmkshatriya.echo.extension.models.DabUser

class Converter {
    // Allow setting the API instance after construction to avoid circular dependency
    var api: DABApi? = null

    fun toUser(user: DabUser): User {
        return User(
            id = user.id.toString(),
            name = user.username
        )
    }

    fun toPlaylist(playlist: DabPlaylist): Playlist {
        return Playlist(
            id = playlist.id,
            title = playlist.name,
            authors = listOf(Artist(id = "user", name = "You")),
            isEditable = false,
            trackCount = playlist.trackCount.toLong()
        )
    }

    fun toTrack(track: DabTrack): Track {
        val streamUrl = "https://dab.yeet.su/api/stream?trackId=${track.id}"

        track.audioQuality?.let { aq ->

        }

        // Map audio quality to numeric levels and create descriptive text
        val (quality, qualityDescription) = when {
            track.audioQuality != null -> {
                val bitDepth = track.audioQuality.maximumBitDepth
                val sampleRate = track.audioQuality.maximumSamplingRate // This is in kHz, not Hz
                val isHiRes = track.audioQuality.isHiRes

                val numericQuality = when {
                    isHiRes && bitDepth >= 24 && sampleRate >= 192.0 -> 5 // 24bit/192kHz+ Hi-Res
                    isHiRes && bitDepth >= 24 && sampleRate >= 96.0 -> 4  // 24bit/96kHz+ Hi-Res
                    isHiRes && bitDepth >= 24 && sampleRate >= 48.0 -> 3  // 24bit/48kHz+ Hi-Res
                    bitDepth >= 16 && sampleRate >= 44.1 -> 2             // CD Quality 16bit/44.1kHz+
                    else -> 1                                             // Standard quality
                }

                val description = if (isHiRes) {
                    "${bitDepth}bit/${sampleRate.toInt()}kHz Hi-Res"
                } else {
                    "${bitDepth}bit/${sampleRate.toInt()}kHz"
                }

                numericQuality to description
            }
            else -> {
                1 to "Standard Quality"
            }
        }

        // Create artist with image for info tab
        val artist = Artist(
            id = track.artistId?.toString() ?: track.artist,
            name = track.artist,
            // Prefer album cover as a fallback for artist image to avoid API call here
            cover = track.albumCover?.toImageHolder()
        )

        // Create album with cover for info tab
        val album = track.albumTitle?.let {
            Album(
                id = track.albumId ?: it,
                title = it,
                // Add album cover from track data
                cover = track.albumCover?.toImageHolder(),
                artists = listOf(artist)
            )
        }

        return Track(
            id = track.id.toString(),
            title = track.title,
            // Add cover to track for the main player display
            cover = track.albumCover?.toImageHolder(),
            artists = listOf(artist),
            album = album,
            streamables = listOf(
                Streamable.server(
                    id = qualityDescription.replace(" ", "_").lowercase(),
                    quality = quality,
                    extras = mapOf(
                        "url" to streamUrl,
                        "qualityDescription" to qualityDescription,
                        "bitDepth" to (track.audioQuality?.maximumBitDepth?.toString() ?: "Unknown"),
                        "sampleRate" to (track.audioQuality?.maximumSamplingRate?.toString() ?: "Unknown"),
                        "isHiRes" to (track.audioQuality?.isHiRes?.toString() ?: "false")
                    )
                )
            ),
            duration = track.duration.toLong() * 1000
        )
    }

    fun toAlbum(album: dev.brahmkshatriya.echo.extension.models.DabAlbum): Album {
        return Album(
            id = album.id,
            title = album.title,
            cover = album.cover?.toImageHolder(),
            artists = listOf(Artist(id = album.artistId?.toString() ?: album.artist, name = album.artist)),
            releaseDate = null, // DAB API returns string date, Echo expects Date - convert if needed
            trackCount = album.trackCount.toLong()
        )
    }

    fun toArtist(artist: dev.brahmkshatriya.echo.extension.models.DabArtist): Artist {
        return Artist(
            id = artist.id.toString(),
            name = artist.name,
            cover = artist.picture?.toImageHolder()
        )
    }

    // Convert a raw lyrics string (plain or LRC) into the Echo Lyrics.Lyric model
    fun toLyricFromText(text: String?): Lyrics.Lyric? {
        if (text == null) return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return Lyrics.Lyric.fromText(trimmed)
    }
}
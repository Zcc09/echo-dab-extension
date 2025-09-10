package dev.brahmkshatriya.echo.extension.models

import kotlinx.serialization.Serializable

@Serializable
data class DabLoginRequest(val email: String, val password: String)

@Serializable
data class DabUserResponse(val user: DabUser?)

@Serializable
data class DabUser(
    val id: Int,
    val username: String,
    val email: String
)

@Serializable
data class DabPlaylistResponse(
    val libraries: List<DabPlaylist>
)

@Serializable
data class DabPlaylist(
    val id: String,
    val name: String,
    val description: String?,
    val isPublic: Boolean,
    val trackCount: Int,
    val createdAt: String
)

@Serializable
data class DabTrackResponse(
    val tracks: List<DabTrack>
)

@Serializable
data class DabTrack(
    val id: Int,
    val title: String,
    val artist: String,
    val artistId: Int?,
    val albumTitle: String?,
    val albumCover: String?,
    val albumId: String?,
    val releaseDate: String?,
    val genre: String?,
    val duration: Int,
    val audioQuality: DabAudioQuality?
)

@Serializable
data class DabLibraryTracksResponse(
    val library: DabLibrary
)

@Serializable
data class DabLibrary(
    val tracks: List<DabTrack>,
    val pagination: DabPagination
)

@Serializable
data class DabPagination(
    val hasMore: Boolean
)

@Serializable
data class DabAudioQuality(
    val maximumBitDepth: Int,
    val maximumSamplingRate: Double,
    val isHiRes: Boolean
)

@Serializable
data class DabStreamResponse(
    val streamUrl: String? = null,
    val url: String? = null,
    val stream: String? = null,
    val link: String? = null
)

@Serializable
data class DabAlbumResponse(
    val albums: List<DabAlbum>
)

@Serializable
data class DabAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: Int?,
    val cover: String?,
    val releaseDate: String?,
    val trackCount: Int
)

@Serializable
data class DabArtistResponse(
    val artists: List<DabArtist>
)

@Serializable
data class DabArtist(
    val id: Int,
    val name: String,
    val picture: String?
)

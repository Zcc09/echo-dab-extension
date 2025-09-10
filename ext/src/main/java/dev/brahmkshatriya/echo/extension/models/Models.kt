package dev.brahmkshatriya.echo.extension.models

import kotlinx.serialization.Serializable

@Serializable
data class DabLoginRequest(val email: String, val password: String)

@Serializable
data class DabUserResponse(val user: DabUser?)

@Serializable
data class DabUser(
    val id: String,
    val username: String,
    val email: String,
    val created_at: String
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
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val albumTitle: String?,
    val albumCover: String?,
    val albumId: String?,
    val releaseDate: String?,
    val genre: String?,
    val duration: Int,
)

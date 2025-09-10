package dev.brahmkshatriya.echo.extension.models

import kotlinx.serialization.Serializable

@Serializable
data class DabAuthResponse(val token: String)

@Serializable
data class DabUserResponse(val data: DabUser)

@Serializable
data class DabUser(
    val id: String,
    val href: String,
    val attributes: Attributes,
) {
    @Serializable
    data class Attributes(
        val name: String,
        val artwork: String? = null,
    )
}

@Serializable
data class DabPlaylistResponse(
    val data: List<DabPlaylist>,
    val meta: Meta,
)

@Serializable
data class DabPlaylist(
    val id: String,
    val href: String,
    val attributes: Attributes,
) {
    @Serializable
    data class Attributes(
        val name: String,
        val artwork: String,
        val curatorName: String? = null,
    )
}

@Serializable
data class DabTrackResponse(
    val data: List<DabTrack>,
    val meta: Meta,
)

@Serializable
data class DabTrack(
    val id: String,
    val href: String,
    val attributes: Attributes,
) {
    @Serializable
    data class Attributes(
        val name: String,
        val artwork: String,
        val artistName: String,
        val albumName: String? = null,
        val url: String,
    )
}

@Serializable
data class Meta(
    val hasMore: Boolean,
)
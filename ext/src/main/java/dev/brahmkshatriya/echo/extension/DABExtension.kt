package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.providers.GlobalSettingsProvider
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.DABParser.toAlbum
import dev.brahmkshatriya.echo.extension.DABParser.toArtist
import dev.brahmkshatriya.echo.extension.DABParser.toLastFmArtist
import dev.brahmkshatriya.echo.extension.DABParser.toLastFmTrack
import dev.brahmkshatriya.echo.extension.DABParser.toPlaylist
import dev.brahmkshatriya.echo.extension.DABParser.toTrack
import dev.brahmkshatriya.echo.extension.DABParser.toUser
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

class DABExtension : ExtensionClient, LoginClient, HomeFeedClient, SearchFeedClient, LibraryFeedClient,
    AlbumClient, ArtistClient, PlaylistClient, TrackClient, LyricsClient, GlobalSettingsProvider {

    override val name = "DAB"
    override val uniqueId = "dev.brahmkshatriya.echo.extension.dab"

    private lateinit var settings: Settings
    private val session by lazy { DABSession(settings) }
    private val okHttpClient by lazy {
        val cookieJar = object : CookieJar {
            private val cookieStore = mutableMapOf<String, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        }
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .build()
    }
    private val api by lazy { DABApi(session, okHttpClient) }
    private val lastFmApi by lazy {
        val apiKey = settings.get("lastfm_api_key") ?: ""
        LastFmApi(apiKey)
    }

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun getSettings(): List<Setting> {
        return listOf(
            Setting.TextInput(
                key = "lastfm_api_key",
                title = "Last.fm API Key",
                subtitle = "Required for the Home feed",
                private = true,
                value = settings.get("lastfm_api_key") ?: ""
            )
        )
    }

    // LoginClient
    override val canLogout = true
    override suspend fun login(inputs: Map<String, String>?): List<User>? {
        if (inputs == null) return null
        val email = inputs["email"] ?: ""
        val password = inputs["password"] ?: ""
        val response = api.callApi(
            path = "/auth/login",
            method = "POST"
        ) {
            put("email", email)
            put("password", password)
        }
        val userJson = response["user"]?.jsonObject ?: throw Exception("Login failed: User data not found")
        val user = userJson.toUser()
        return listOf(user)
    }

    override suspend fun logout() {
        session.logout()
        try {
            api.callApi(path = "/auth/logout", method = "POST")
        } catch (e: Exception) {
            // Ignore
        }
    }

    override suspend fun getCurrentUser(): User? {
        return session.getLoggedInUser()
    }

    override fun setLoginUser(user: User?) {
        if (user != null) {
            session.login(user)
        } else {
            session.logout()
        }
    }

    override suspend fun getLoginSettings(): List<Setting> {
        return listOf(
            Setting.TextInput(
                key = "email",
                title = "Email",
                private = false,
                value = ""
            ),
            Setting.TextInput(
                key = "password",
                title = "Password",
                private = true,
                value = ""
            )
        )
    }

    // HomeFeedClient
    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val response = lastFmApi.getHomeFeed()
        val sections = response["sections"] as? JsonArray ?: return Feed.Empty
        val shelves = sections.mapNotNull { section ->
            val sectionJson = section.jsonObject
            val title = sectionJson["title"]?.jsonPrimitive?.content ?: "Untitled"
            val itemsJson = sectionJson["items"] as? JsonArray ?: return@mapNotNull null
            val type = sectionJson["type"]?.jsonPrimitive?.content
            when (type) {
                "lastfm:track" -> {
                    val tracks = itemsJson.mapNotNull { it.jsonObject.toLastFmTrack() }
                    Shelf.Lists(title, tracks)
                }
                "lastfm:artist" -> {
                    val artists = itemsJson.mapNotNull { it.jsonObject.toLastFmArtist() }
                    Shelf.Grid(title, artists)
                }
                else -> null
            }
        }
        return Feed(shelves)
    }

    // SearchFeedClient
    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        return supervisorScope {
            val tracksDeferred = async {
                api.callApi(path = "/search", queryParams = mapOf("q" to query, "type" to "track"))
            }
            val albumsDeferred = async {
                api.callApi(path = "/search", queryParams = mapOf("q" to query, "type" to "album"))
            }
            val artistsDeferred = async {
                api.callApi(path = "/search", queryParams = mapOf("q" to query, "type" to "artist"))
            }

            val shelves = mutableListOf<Shelf>()

            try {
                val tracksJson = tracksDeferred.await()["results"] as? JsonArray
                if (!tracksJson.isNullOrEmpty()) {
                    val tracks = tracksJson.mapNotNull { it.jsonObject.toTrack() }
                    shelves.add(Shelf.Lists("Tracks", tracks))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                val albumsJson = albumsDeferred.await()["results"] as? JsonArray
                if (!albumsJson.isNullOrEmpty()) {
                    val albums = albumsJson.mapNotNull { it.jsonObject.toAlbum() }
                    shelves.add(Shelf.Grid("Albums", albums))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                val artistsJson = artistsDeferred.await()["results"] as? JsonArray
                if (!artistsJson.isNullOrEmpty()) {
                    val artists = artistsJson.mapNotNull { it.jsonObject.toArtist() }
                    shelves.add(Shelf.Grid("Artists", artists))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            Feed(shelves, emptyList())
        }
    }

    // LibraryFeedClient
    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val response = api.callApi(path = "/libraries")
        val libraries = (response["libraries"] as? JsonArray)?.mapNotNull { it.jsonObject.toPlaylist() }
        val shelf = if (libraries != null) Shelf.Grid("Libraries", libraries) else null
        return Feed(if (shelf != null) listOf(shelf) else emptyList())
    }

    // AlbumClient
    override suspend fun loadAlbum(album: Album): Album {
        return album
    }

    override suspend fun loadTracks(album: Album): Feed<Track> {
        val albumId = album.id.removePrefix("album:")
        val response = api.callApi(path = "/album/$albumId")
        val albumJson = response["album"]?.jsonObject ?: return Feed.Empty
        val tracksJson = albumJson["tracks"] as? JsonArray ?: return Feed.Empty
        val tracks = tracksJson.mapNotNull { it.jsonObject.toTrack() }
        return Feed(tracks)
    }

    // ArtistClient
    override suspend fun loadArtist(artist: Artist): Artist {
        return artist
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val artistId = artist.id.removePrefix("artist:")
        val response = api.callApi(
            path = "/discography",
            queryParams = mapOf("artistId" to artistId)
        )

        val artistName = response["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: artist.name
        val albumsJson = response["albums"] as? JsonArray ?: JsonArray(emptyList())

        val albums = albumsJson.mapNotNull { it.jsonObject.toAlbum() }
        val albumShelf = Shelf.Grid("$artistName's Albums", albums)

        val topTracks = albumsJson
            .take(10)
            .flatMap { album ->
                val tracksJson = album.jsonObject["tracks"] as? JsonArray ?: emptyList()
                tracksJson.take(2)
            }
            .mapNotNull { it.jsonObject.toTrack() }
        val topTracksShelf = Shelf.Lists("Top Tracks", topTracks)

        return Feed(listOf(topTracksShelf, albumShelf))
    }

    // PlaylistClient
    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        return playlist
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val playlistId = playlist.id.removePrefix("playlist:")
        val response = api.callApi(
            path = "/libraries/$playlistId",
            queryParams = mapOf("page" to "1") // API is 1-indexed
        )

        val libraryJson = response["library"]?.jsonObject ?: return Feed.Empty
        val tracksJson = libraryJson["tracks"] as? JsonArray ?: return Feed.Empty
        val paginationJson = libraryJson["pagination"]?.jsonObject

        val tracks = tracksJson.mapNotNull { it.jsonObject.toTrack() }
        val hasMore = paginationJson?.get("hasMore")?.jsonPrimitive?.boolean ?: false

        return Feed(
            data = tracks,
            nextPage = if (hasMore) 2 else null
        )
    }

    // TrackClient
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        return track
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val response = api.callApi(
            path = "/stream",
            queryParams = mapOf("trackId" to streamable.id)
        )
        val streamUrl = response["streamUrl"]?.jsonPrimitive?.content
            ?: throw Exception("No stream URL found")

        val cookieHeader = api.getCookies(streamUrl)
        val headers = if (cookieHeader.isNotEmpty()) mapOf("Cookie" to cookieHeader) else emptyMap()

        return Streamable.Media.create(
            url = streamUrl,
            headers = headers
        )
    }

    // LyricsClient
    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        val artistName = track.artists.firstOrNull()?.name ?: return Feed.Empty()
        val trackTitle = track.title

        return try {
            val response = api.callApi(
                path = "/lyrics",
                queryParams = mapOf("artist" to artistName, "title" to trackTitle)
            )

            val lyricsText = response["lyrics"]?.jsonPrimitive?.content
            val isUnsynced = response["unsynced"]?.jsonPrimitive?.boolean ?: true

            if (lyricsText != null) {
                val lyrics = Lyrics(lyricsText, !isUnsynced)
                Feed.Single(listOf(lyrics))
            } else {
                Feed.Empty()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Feed.Empty()
        }
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        return lyrics
    }
}
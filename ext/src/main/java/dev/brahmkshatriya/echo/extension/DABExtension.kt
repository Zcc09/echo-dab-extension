package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.extension.clients.DABAlbumClient
import dev.brahmkshatriya.echo.extension.clients.DABArtistClient
import dev.brahmkshatriya.echo.extension.clients.DABHomefeedClient
import dev.brahmkshatriya.echo.extension.clients.DABLibraryClient
import dev.brahmkshatriya.echo.extension.clients.DABLoginClient
import dev.brahmkshatriya.echo.extension.clients.DABLyricsClient
import dev.brahmkshatriya.echo.extension.clients.DABPlaylistClient
import dev.brahmkshatriya.echo.extension.clients.DABSearchClient
import dev.brahmkshatriya.echo.extension.clients.DABTrackClient
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

class DABExtension : Extension<ExtensionClient>() {

    override val name = "DAB"

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

    private val session by lazy { DABSession(settings) }
    private val api by lazy { DABApi(session, okHttpClient) }
    private val lastFmApi by lazy {
        val apiKey = settings.get("lastfm_api_key") ?: ""
        LastFmApi(apiKey)
    }

    override val audioStreamProvider by lazy { AudioStreamProvider(api) }

    override val clients by lazy {
        listOf(
            DABLoginClient(api, session),
            DABHomefeedClient(lastFmApi),
            DABSearchClient(api),
            DABLibraryClient(api),
            DABAlbumClient(api),
            DABArtistClient(api),
            DABPlaylistClient(api),
            DABTrackClient(api),
            DABLyricsClient(api)
        )
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
}
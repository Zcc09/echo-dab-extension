package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.json.Json

class DABSession(
    private val settings: Settings,
) {
    private var user: User? = settings.get<String?>("user", null)?.let {
        try {
            Json.decodeFromString(User.serializer(), it)
        } catch (e: Exception) {
            null
        }
    }

    fun login(user: User) {
        this.user = user
        settings.set("user", Json.encodeToString(User.serializer(), user))
    }

    fun logout() {
        user = null
        settings.set("user", null)
    }

    fun getLoggedInUser(): User? {
        return user
    }
}
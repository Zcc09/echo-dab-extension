package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Settings

class DABSession(
    private val settings: Settings,
) {
    private var user: User? = settings.get("user")

    fun login(user: User) {
        this.user = user
        settings.set("user", user)
    }

    fun logout() {
        user = null
        settings.set("user", null)
    }

    fun getLoggedInUser(): User? {
        return user
    }
}
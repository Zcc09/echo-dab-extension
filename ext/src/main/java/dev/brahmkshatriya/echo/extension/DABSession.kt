package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Settings

class DABSession(
    private val settings: Settings,
) {
    // Use `load` with a serializer to retrieve the saved user.
    private var user: User? = settings.load("user", User.serializer())

    fun login(user: User) {
        this.user = user
        // Use `save` with a serializer to store the user.
        settings.save("user", user, User.serializer())
    }

    fun logout() {
        user = null
        // Use `save` to store null, effectively clearing the user.
        settings.save("user", null, User.serializer())
    }

    fun getLoggedInUser(): User? {
        return user
    }
}
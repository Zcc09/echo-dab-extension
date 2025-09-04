package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.extension.DABApi
import dev.brahmkshatriya.echo.extension.DABParser.toUser
import dev.brahmkshatriya.echo.extension.DABSession
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class DABLoginClient(
    private val api: DABApi,
    private val session: DABSession
) : LoginClient {

    override val canLogout = true

    override suspend fun login(inputs: Map<String, String>?): List<Setting>? {
        // If inputs are null, the app is asking for the fields to display.
        if (inputs == null) {
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

        // If inputs are provided, process the login.
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
        session.login(user)

        // Return null to signify that the login was successful.
        return null
    }

    override suspend fun logout() {
        session.logout()
        try {
            api.callApi(path = "/auth/logout", method = "POST")
        } catch (e: Exception) {
            // Ignore errors here as the local session is already cleared.
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
}
package dev.brahmkshatriya.echo.extension

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class RateLimitInterceptor(private val minimumDelayMillis: Long) : Interceptor {
    private var lastRequestTime = 0L

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(this) {
            val now = System.currentTimeMillis()
            val timeSinceLastRequest = now - lastRequestTime
            val timeToWait = minimumDelayMillis - timeSinceLastRequest

            if (timeToWait > 0) {
                try {
                    Thread.sleep(timeToWait)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            lastRequestTime = System.currentTimeMillis()
        }
        return chain.proceed(chain.request())
    }
}
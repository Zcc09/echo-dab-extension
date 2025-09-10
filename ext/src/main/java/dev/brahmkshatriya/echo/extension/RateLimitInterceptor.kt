package dev.brahmkshatriya.echo.extension

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Adaptive rate limiting interceptor.
 * - Uses a very small base delay between requests to avoid overwhelming servers.
 * - On HTTP 429 or if Retry-After header is present, increases the delay (exponential backoff)
 *   and retries once.
 * - On successful responses, slowly decays the delay back toward the base delay.
 * - If an unexpected error occurs inside the interceptor, it falls back to a conservative
 *   delay and a simpler behavior to avoid causing crashes or extreme slowdowns.
 */
class RateLimitInterceptor(
    private val baseDelayMillis: Long = 10,
    private val maxDelayMillis: Long = 2000,
    private val decayStepMillis: Long = 50,
    private val fallbackDelayMillis: Long = 100
) : Interceptor {
    private val lastRequestTime = AtomicLong(0L)
    private val currentDelay = AtomicLong(baseDelayMillis)

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        return try {
            // Ensure at least currentDelay millis between requests
            synchronized(this) {
                val now = System.currentTimeMillis()
                val timeSinceLast = now - lastRequestTime.get()
                val delay = currentDelay.get()
                val toWait = delay - timeSinceLast
                if (toWait > 0) {
                    try {
                        Thread.sleep(toWait)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                lastRequestTime.set(System.currentTimeMillis())
            }

            // Try the request, with a single retry on 429 (respecting Retry-After if present)
            var attempt = 0
            var response = chain.proceed(chain.request())
            while (attempt < 1 && (response.code == 429 || hasRetryAfter(response))) {
                // If server signalled Retry-After use it; otherwise use exponential backoff
                val retryAfterHeader = response.header("Retry-After")
                val waitMillis = parseRetryAfterMillis(retryAfterHeader) ?: exponentialBackoffAndIncrease()

                try {
                    Thread.sleep(waitMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                attempt++
                response.close()
                response = chain.proceed(chain.request())
            }

            // On success responses, decay the currentDelay toward baseDelay
            if (response.isSuccessful) {
                decayDelay()
            } else if (response.code == 429) {
                // If still getting 429 increase delay more aggressively
                increaseDelay()
            }

            response
        } catch (_: Throwable) {
            // Fallback: if anything unexpected fails inside the interceptor, don't crash the caller.
            // Set a conservative delay and perform a minimal handling: single request and optional sleep on 429.
            try {
                currentDelay.set(fallbackDelayMillis)
            } catch (_: Exception) {
                // ignore
            }

            val response = chain.proceed(chain.request())
            if (response.code == 429) {
                try {
                    Thread.sleep(fallbackDelayMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            response
        }
    }

    private fun hasRetryAfter(response: Response): Boolean {
        val header = response.header("Retry-After")
        return !header.isNullOrBlank()
    }

    private fun parseRetryAfterMillis(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        return try {
            // Retry-After can be seconds or a HTTP-date; try seconds first
            val seconds = header.trim().toLongOrNull()
            if (seconds != null) return TimeUnit.SECONDS.toMillis(seconds)

            // If it's a date, attempt to parse as epoch seconds (best-effort); otherwise ignore
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun exponentialBackoffAndIncrease(): Long {
        val prev = currentDelay.get()
        val next = (prev * 2).coerceAtMost(maxDelayMillis)
        currentDelay.set(next)
        return next
    }

    private fun increaseDelay() {
        currentDelay.getAndUpdate { prev -> (prev * 2).coerceAtMost(maxDelayMillis) }
    }

    private fun decayDelay() {
        currentDelay.getAndUpdate { prev ->
            if (prev <= baseDelayMillis) baseDelayMillis
            else (prev - decayStepMillis).coerceAtLeast(baseDelayMillis)
        }
    }
}
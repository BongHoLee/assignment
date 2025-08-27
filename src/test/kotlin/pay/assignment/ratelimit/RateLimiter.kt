package pay.assignment.ratelimit

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

interface RateLimiter {
    fun isAllowed(userId: UserId, requestInformation: RequestInformation, rule: RateLimitRule): Boolean
}

data class RequestInformation(
    val timestamp: Instant
)

data class UserId(val id: String)

data class RateLimitRule(
    val maxRequest: Int,
    val timeWindowSeconds: Int
)

@Component
class SlidingWindowRateLimiter(
    private val windows: ConcurrentHashMap<UserId, SlidingWindow> = ConcurrentHashMap()
) : RateLimiter {

    override fun isAllowed(
        userId: UserId,
        requestInformation: RequestInformation,
        rule: RateLimitRule
    ): Boolean {
        val currentTime = requestInformation.timestamp.epochSecond
        val window = windows.computeIfAbsent(userId) { SlidingWindow(rule.timeWindowSeconds) }

        return window.tryAcquire(currentTime, rule.maxRequest)
    }
}

class SlidingWindow(private val timeWindowSeconds: Int) {
    private val requestTimestamps = ConcurrentHashMap<Long, AtomicInteger>()

    fun tryAcquire(currentTime: Long, maxRequest: Int): Boolean {
        cleanup(currentTime)

        val currentWindow = currentTime / timeWindowSeconds
        val count = requestTimestamps.computeIfAbsent(currentWindow) { AtomicInteger(0) }

        return if (count.incrementAndGet() <= maxRequest) {
            true
        } else {
            count.decrementAndGet()
            false
        }
    }

    private fun cleanup(currentTime: Long) {
        val expirationTime = currentTime - timeWindowSeconds
        requestTimestamps.keys.removeIf { it < expirationTime / timeWindowSeconds }
    }
}
package pay.assignment.ratelimit

import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

interface RateLimiter {
    fun isAllowed(userId: UserId, requestInformation: RequestInformation, rule: RateLimitRule): Boolean
}

data class RequestInformation(
    val timestamp: LocalDateTime
)

data class UserId(val id: String)

data class RateLimitRule(
   val maxRequest: Int,
   val timeWindowSeconds: Int
)

@Component
class UserIdRateLimiter(
    private val windows: ConcurrentHashMap<UserId, FixedTimeWindow> = ConcurrentHashMap()
) : RateLimiter {


    override fun isAllowed(
        userId: UserId,
        requestInformation: RequestInformation,
        rule: RateLimitRule,
    ): Boolean {

        val currentTime = requestInformation.timestamp
        val window = windows.computeIfAbsent(userId) { FixedTimeWindow(currentTime) }

        synchronized(userId) {
            return window.isAcceptable(requestInformation, rule)
        }
    }
}

class FixedTimeWindow(
    private var startTime: LocalDateTime,
    private var requestCount: Int = 0
) {

    fun isAcceptable(requestInformation: RequestInformation, rule: RateLimitRule): Boolean {
        if (isWithinWindow(requestInformation.timestamp, rule.timeWindowSeconds)) {
            if (requestCount < rule.maxRequest) {
                increment()
                return true
            } else {
                return false
            }
        } else {
            reset(requestInformation.timestamp)
            return true
        }
    }

    fun isWithinWindow(currentTime: LocalDateTime, windowSeconds: Int): Boolean {
        return !currentTime.isBefore(startTime) && currentTime.isBefore(startTime.plusSeconds(windowSeconds.toLong()))
    }

    fun getRequestCount(): Int = requestCount

    fun increment() {
        requestCount++
    }

    fun reset(startTime: LocalDateTime) {
        this.startTime = startTime
        this.requestCount = 1
    }
}
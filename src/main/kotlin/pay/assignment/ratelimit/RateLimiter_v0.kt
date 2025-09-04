package pay.assignment.ratelimit

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface RateLimiter {
    fun check(userId: UserId, rule: RateLimitRule): Decision
    fun checkAt(userId: UserId, rule: RateLimitRule, now: Instant): Decision
}

data class UserId(val id: String)

data class RateLimitRule(
    val maxRequest: Int,
    val timeWindowSeconds: Int
)

data class Decision(
    val allowed: Boolean,
    val remaining: Int,
    val retryAfterMillis: Long
)

/** userId + windowSeconds 별 상태 키 */
data class StateKey(val userId: UserId, val windowSeconds: Int)

/**
 * 고정 윈도우 상태 (드래프트 텀플링)
 * - windowStartMs: 이 상태가 현재 보고 있는 윈도우의 "시작 시각(ms)". ex) floor(now/windowMs)*windowMs
 * - count: 해당 창에서 허용된 요청 수
 * - lastSeenMillis: TTL 청소용
 *
 * 경계 규칙: [windowStartMs, windowStartMs + windowMs)
 */
data class DriftWindowState(
    var windowStartMs: Long,
    var count: Int,
    var lastSeenMillis: Long
) {
    companion object {
        fun init(nowMs: Long) =
            DriftWindowState(nowMs, 0, nowMs)
    }

    fun decide(nowMs: Long, windowMs: Long, limit: Int): Decision {
        rollingWindowIfNeeded(nowMs, windowMs)

        val allowed = isRequestAllowed(limit)
        if (allowed) incrementCount()
        updateLastSeen(nowMs)

        return createDecision(allowed, limit, windowMs, nowMs)
    }

    private fun isRequestAllowed(limit: Int): Boolean {
        return count < limit
    }

    private fun incrementCount() {
        count += 1
    }

    private fun updateLastSeen(nowMs: Long) {
        lastSeenMillis = nowMs
    }

    private fun createDecision(allowed: Boolean, limit: Int, windowMs: Long, nowMs: Long): Decision {
        val remaining = calculateRemaining(allowed, limit)
        val retryAfter = calculateRetryAfter(allowed, windowMs, nowMs)
        return Decision(allowed, remaining, retryAfter)
    }

    private fun calculateRemaining(allowed: Boolean, limit: Int): Int {
        return if (allowed) limit - count else 0
    }

    private fun calculateRetryAfter(allowed: Boolean, windowMs: Long, nowMs: Long): Long {
        return if (allowed) 0L
        else (nextWindowStartMs(windowMs, nowMs)).coerceAtLeast(0L)
    }

    private fun nextWindowStartMs(windowMs: Long, nowMs: Long): Long =
        (windowStartMs + windowMs) - nowMs

    private fun rollingWindowIfNeeded(nowMs: Long, windowMs: Long) {
        if(nowMs - windowStartMs >= windowMs){
            windowStartMs = nowMs
            count = 0
        }
    }
}


class FixedWindowLimiter(
    private val clock: Clock = Clock.systemUTC(),
    private val states: ConcurrentHashMap<StateKey, DriftWindowState> = ConcurrentHashMap()
) : RateLimiter {

    override fun check(userId: UserId, rule: RateLimitRule): Decision {
        return checkAt(userId, rule, Instant.now(clock))
    }

    override fun checkAt(userId: UserId, rule: RateLimitRule, now: Instant): Decision {
        val nowMs = now.toEpochMilli()
        val windowMs = rule.timeWindowSeconds * 1000L
        val key = StateKey(userId, rule.timeWindowSeconds)

        val state = states.computeIfAbsent(key) { DriftWindowState.init(nowMs) }

        return synchronized(state) {
            state.decide(nowMs, windowMs, rule.maxRequest)
        }
    }

    /** TTL 청소: @Scheduled 등으로 주기 호출 권장 */
    fun cleanupExpired(
        idleMillis: Long = 10 * 60 * 1000L,
        now: Instant = Instant.now(clock)
    ) {
        val nowMs = now.toEpochMilli()
        states.entries.removeIf { (_, s) -> (nowMs - s.lastSeenMillis) >= idleMillis }
    }
}

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
 * 고정 윈도우 상태 (전역 고정 경계)
 * - windowStartMs: 이 상태가 현재 보고 있는 윈도우의 "시작 시각(ms)". ex) floor(now/windowMs)*windowMs
 * - count: 해당 창에서 허용된 요청 수
 * - lastSeenMillis: TTL 청소용
 *
 * 경계 규칙: [windowStartMs, windowStartMs + windowMs)
 */
data class FixedWindowState(
    var windowStartMs: Long,
    var count: Int,
    var lastSeenMillis: Long
) {
    companion object {
        fun init(nowMs: Long, windowMs: Long) =
            FixedWindowState(snapToWindowStart(nowMs, windowMs), 0, nowMs)

        fun snapToWindowStart(tsMs: Long, windowMs: Long): Long =
            (tsMs / windowMs) * windowMs

        fun nextWindowStartMs(windowStartMs: Long, windowMs: Long): Long =
            windowStartMs + windowMs
    }

    /**
     * 같은 State에 대해서만 동시 접근되도록 바깥에서 synchronized(state) 보장.
     * 판정은:
     * 1) 현재 now가 속한 창의 시작시각(currentStart)을 계산
     * 2) 상태의 windowStartMs와 다르면 창 교체(카운트 리셋)
     * 3) count < limit 이면 허용하고 증가, 아니면 거절 + retryAfter 계산
     */
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
        else (nextWindowStartMs(windowStartMs, windowMs) - nowMs).coerceAtLeast(0L)
    }

    private fun rollingWindowIfNeeded(nowMs: Long, windowMs: Long) {
        val currentStart = snapToWindowStart(nowMs, windowMs)
        if (currentStart != windowStartMs) {
            windowStartMs = currentStart
            count = 0
        }
    }
}

/**
 * 타임스탬프(ms)만으로 판단하는 고정 윈도우 리미터 (전역 고정 경계).
 * - 상태 로직은 FixedWindowState로 캡슐화
 * - per-state synchronized로 간단히 직렬화
 * - Clock 주입으로 테스트 용이
 */
class FixedWindowLimiter(
    private val clock: Clock = Clock.systemUTC(),
    private val states: ConcurrentHashMap<StateKey, FixedWindowState> = ConcurrentHashMap()
) : RateLimiter {

    override fun check(userId: UserId, rule: RateLimitRule): Decision {
        return checkAt(userId, rule, Instant.now(clock))
    }

    override fun checkAt(userId: UserId, rule: RateLimitRule, now: Instant): Decision {
        val nowMs = now.toEpochMilli()
        val windowMs = rule.timeWindowSeconds * 1000L
        val key = StateKey(userId, rule.timeWindowSeconds)

        val state = states.computeIfAbsent(key) { FixedWindowState.init(nowMs, windowMs) }

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

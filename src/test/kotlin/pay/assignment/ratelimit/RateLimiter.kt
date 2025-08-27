package pay.assignment.ratelimit

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 고정 윈도우(rate limit: 최근 T초 구간에서 최대 N회) */
interface RateLimiter {
    /** 실사용용: 내부 clock 기준으로 now를 읽어서 판단 */
    fun check(userId: UserId, rule: RateLimitRule): Decision

    /** 테스트/시뮬레이션용: 외부에서 now를 주입해 결정적 검증 */
    fun checkAt(userId: UserId, rule: RateLimitRule, now: Instant): Decision
}

data class UserId(val id: String)

/** 예: timeWindowSeconds=5, maxRequest=3 → 5초 동안 최대 3회 허용 */
data class RateLimitRule(
    val maxRequest: Int,
    val timeWindowSeconds: Int
)

/** 판정 결과 + 운영에 유용한 메타 정보 */
data class Decision(
    val allowed: Boolean,
    val remaining: Int,          // 허용 시 남은 허용량(같은 창 기준), 거절 시 0
    val retryAfterMillis: Long   // 거절 시 다음 창 시작까지 남은 ms, 허용 시 0
)

/** userId + windowSeconds 로 상태를 분리 (규칙이 사용자별로 달라도 안전) */
 data class StateKey(val userId: UserId, val windowSeconds: Int)

/**
 * 고정 윈도우 상태
 * - windowKey: floor(nowMillis / windowMs)
 * - count: 해당 창에서 허용된 요청 수
 * - lastSeenMillis: TTL 청소용 (오랫동안 미사용 상태 제거)
 *
 * 경계 규칙: [start, start + T) (좌폐우개)
 *  - 정확히 T초가 지난 이벤트는 *새 창*으로 간주
 */
 data class FixedWindowState(
    var windowKey: Long,
    var count: Int,
    var lastSeenMillis: Long
) {
    companion object {
        fun init(nowMs: Long, windowMs: Long) =
            FixedWindowState(windowKey(nowMs, windowMs), 0, nowMs)

        fun windowKey(tsMs: Long, windowMs: Long): Long = tsMs / windowMs
        fun nextWindowStartMs(key: Long, windowMs: Long): Long = (key + 1) * windowMs
    }

    /** 같은 유저/규칙(StateKey) 단위로만 동시 접근되도록 밖에서 synchronized(state) 보장 */
    fun decide(nowMs: Long, windowMs: Long, limit: Int): Decision {
        rollWindowIfNeeded(nowMs, windowMs)

        val allowed = count < limit
        if (allowed) {
            count += 1
        }
        lastSeenMillis = nowMs

        val remaining = if (allowed) (limit - count) else 0
        val retryAfter =
            if (allowed) 0L
            else (nextWindowStartMs(windowKey, windowMs) - nowMs).coerceAtLeast(0L)

        return Decision(allowed, remaining, retryAfter)
    }

    private fun rollWindowIfNeeded(nowMs: Long, windowMs: Long) {
        val currentKey = windowKey(nowMs, windowMs)
        if (currentKey != windowKey) {
            // 새 창 시작: 누적 카운트 리셋
            windowKey = currentKey
            count = 0
        }
    }
}

/**
 * 스타일 A 구현:
 *  - 상태 로직을 FixedWindowState에 캡슐화
 *  - userId+rule(windowSeconds)별 state를 ConcurrentHashMap에 보관
 *  - 같은 state에 대한 동시 접근은 synchronized(state)로 직렬화(간결&명확)
 *  - Clock 주입으로 테스트 용이성↑
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

        // 상태 조회/생성
        val state = states.computeIfAbsent(key) { FixedWindowState.init(nowMs, windowMs) }

        // 같은 (userId, windowSeconds) 상태에 대해서만 직렬화
        return synchronized(state) {
            state.decide(nowMs, windowMs, rule.maxRequest)
        }
    }

    /**
     * TTL 청소: 오랫동안 접근이 없는 상태 제거
     * - 운영에서는 @Scheduled 로 주기 실행하거나, 요청 중 샘플링 호출 권장
     * - idleMillis 기본 10분
     */
    fun cleanupExpired(
        idleMillis: Long = 10 * 60 * 1000L,
        now: Instant = Instant.now(clock)
    ) {
        val nowMs = now.toEpochMilli()
        states.entries.removeIf { (_, s) -> (nowMs - s.lastSeenMillis) >= idleMillis }
    }
}

package pay.assignment.ratelimit_v3

import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Clock
import java.time.Instant

/**
 * Redis 기반 Sliding Window Rate Limiter
 * 
 * 특징:
 * - Sliding Window 알고리즘으로 정확한 시간 윈도우 내 요청 수 제한
 * - Redis Sorted Set을 활용한 타임스탬프 기반 요청 추적
 * - 이전 요청들의 정확한 시간 정보 유지
 * - Fixed Window의 경계 효과 문제 해결
 * 
 * 작동 원리:
 * 1. 각 요청을 타임스탬프와 함께 Sorted Set에 저장
 * 2. 현재 시간 기준으로 윈도우 범위 밖의 오래된 요청 제거
 * 3. 윈도우 내 요청 수를 카운트하여 제한 여부 판단
 * 4. 새로운 요청을 Sorted Set에 추가
 */
class SlidingWindowRateLimiter(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * Redis Lua Script를 통한 원자적 Sliding Window 구현
     * 
     * 알고리즘:
     * 1. 현재 시간 기준으로 윈도우 범위 계산 (now - windowMs ~ now)
     * 2. 윈도우 범위 밖의 오래된 요청들 제거 (ZREMRANGEBYSCORE)
     * 3. 현재 윈도우 내 요청 수 카운트 (ZCARD)
     * 4. 제한 이내인 경우 새 요청 타임스탬프 추가 (ZADD)
     * 5. TTL 설정으로 메모리 관리
     * 
     * 반환값: [allowed, currentCount, oldestRequestTime]
     * - allowed: 요청 허용 여부 (0 또는 1)
     * - currentCount: 현재 윈도우 내 요청 수
     * - oldestRequestTime: 윈도우 내 가장 오래된 요청 시간
     */
    private val luaScript = DefaultRedisScript<List<Any>>().apply {
        setScriptText("""
            local key = KEYS[1]
            local windowMs = tonumber(ARGV[1])
            local maxRequests = tonumber(ARGV[2])
            local nowMs = tonumber(ARGV[3])
            local requestId = ARGV[4]
            
            -- 윈도우 시작 시간 계산 (현재시간 - 윈도우크기)
            local windowStartMs = nowMs - windowMs
            
            -- 윈도우 범위 밖의 오래된 요청들 제거
            redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStartMs)
            
            -- 현재 윈도우 내 요청 수 조회
            local currentCount = redis.call('ZCARD', key)
            
            -- 요청 허용 여부 판단
            local allowed = 0
            if currentCount < maxRequests then
                allowed = 1
                -- 새 요청을 타임스탬프와 함께 추가
                redis.call('ZADD', key, nowMs, requestId)
                currentCount = currentCount + 1
            end
            
            -- TTL 설정 (윈도우 크기의 2배로 여유있게)
            redis.call('EXPIRE', key, math.ceil(windowMs / 1000 * 2))
            
            -- 윈도우 내 가장 오래된 요청 시간 조회
            local oldestRequests = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
            local oldestRequestTime = 0
            if #oldestRequests > 0 then
                oldestRequestTime = tonumber(oldestRequests[2])
            end
            
            return {allowed, currentCount, oldestRequestTime}
        """)
        returnType = List::class.java
    }

    /**
     * Sliding Window 기반 Rate Limiting 확인
     * 
     * @param userId 사용자 ID
     * @param rule Sliding Window 규칙
     * @return Rate Limiting 결과
     */
    fun check(
        userId: String,
        rule: SlidingWindowRule,
        now: Instant = Instant.now(clock)
    ): SlidingWindowDecision {
        val nowMs = now.toEpochMilli()
        val windowMs = rule.windowSeconds * 1000L
        val key = "sliding_window:${userId}:${rule.maxRequests}_${rule.windowSeconds}"
        
        // 고유한 요청 ID 생성 (시간 + 랜덤)
        val requestId = "${nowMs}_${(Math.random() * 1000).toInt()}"
        
        val result = redisTemplate.execute(
            luaScript,
            listOf(key),
            windowMs,
            rule.maxRequests,
            nowMs,
            requestId
        ) as List<Any>
        
        val allowed = (result[0] as Number).toInt() == 1
        val currentCount = (result[1] as Number).toInt()
        val oldestRequestTime = (result[2] as Number).toLong()
        
        // 다음 요청 가능 시간 계산
        val retryAfterMs = if (allowed) 0L else {
            if (oldestRequestTime > 0) {
                val nextAvailableTime = oldestRequestTime + windowMs
                (nextAvailableTime - nowMs).coerceAtLeast(0L)
            } else {
                windowMs // 윈도우 크기만큼 대기
            }
        }
        
        return SlidingWindowDecision(
            allowed = allowed,
            currentCount = currentCount,
            maxRequests = rule.maxRequests,
            windowSeconds = rule.windowSeconds,
            retryAfterMs = retryAfterMs,
            oldestRequestTime = if (oldestRequestTime > 0) Instant.ofEpochMilli(oldestRequestTime) else null
        )
    }
}

/**
 * Sliding Window Rule 설정
 * 
 * @param maxRequests 윈도우 내 최대 요청 수
 * @param windowSeconds 슬라이딩 윈도우 크기 (초)
 * 
 * 예시: maxRequests=5, windowSeconds=10
 * -> 최근 10초 동안 최대 5개 요청 허용
 */
data class SlidingWindowRule(
    val maxRequests: Int,
    val windowSeconds: Int
)

/**
 * Sliding Window 처리 결과
 * 
 * @param allowed 요청 허용 여부
 * @param currentCount 현재 윈도우 내 요청 수
 * @param maxRequests 최대 허용 요청 수
 * @param windowSeconds 윈도우 크기 (초)
 * @param retryAfterMs 다음 요청까지 대기 시간 (밀리초)
 * @param oldestRequestTime 윈도우 내 가장 오래된 요청 시간
 */
data class SlidingWindowDecision(
    val allowed: Boolean,
    val currentCount: Int,
    val maxRequests: Int,
    val windowSeconds: Int,
    val retryAfterMs: Long,
    val oldestRequestTime: Instant?
)
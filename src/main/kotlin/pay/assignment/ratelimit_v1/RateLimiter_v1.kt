package pay.assignment.ratelimit_v1

import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Clock
import java.time.Instant

/**
 * Rate Limiter 인터페이스
 * 사용자별로 API 호출 횟수를 제한하는 기능을 제공
 */
interface RateLimiter {
    /**
     * 현재 시점에서 사용자의 요청이 허용되는지 확인
     */
    fun check(userId: UserId, rule: RateLimitRule): Decision
    
    /**
     * 특정 시점에서 사용자의 요청이 허용되는지 확인
     */
    fun checkAt(userId: UserId, rule: RateLimitRule, now: Instant): Decision
}

/**
 * 사용자 ID를 나타내는 데이터 클래스
 */
data class UserId(val id: String)

/**
 * Rate Limit 규칙을 정의하는 데이터 클래스
 * @param maxRequest 시간 윈도우 내 최대 요청 횟수
 * @param timeWindowSeconds 시간 윈도우 크기 (초)
 */
data class RateLimitRule(
    val maxRequest: Int,
    val timeWindowSeconds: Int
)

/**
 * Rate Limiter의 판단 결과
 * @param allowed 요청이 허용되는지 여부
 * @param remaining 남은 요청 횟수
 * @param retryAfterMillis 다음 요청까지 기다려야 하는 시간 (밀리초)
 */
data class Decision(
    val allowed: Boolean,
    val remaining: Int,
    val retryAfterMillis: Long
)

/**
 * Redis 기반 Fixed Window Rate Limiter
 * 
 * 특징:
 * - Fixed Window 알고리즘을 사용하여 정확한 시간 윈도우별 요청 횟수 제한
 * - Redis의 원자적 연산(Lua Script)을 통해 동시성 제어
 * - 자동 TTL 관리로 메모리 효율성 확보
 * - 사용자별 독립적인 상태 관리
 */
class RedisRateLimiter(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val clock: Clock = Clock.systemUTC()
) : RateLimiter {

    /**
     * Redis Lua Script를 통한 원자적 Rate Limiting 구현
     * 
     * 알고리즘:
     * 1. 현재 시간 기준으로 윈도우 시작 시간 계산
     * 2. Redis에서 해당 윈도우의 요청 카운트 조회
     * 3. 제한을 초과하지 않았다면 카운트 증가
     * 4. TTL 설정으로 자동 정리
     * 
     * 반환값: [allowed, count, windowStartMs]
     * - allowed: 요청 허용 여부 (0 또는 1)
     * - count: 현재 윈도우의 요청 수
     * - windowStartMs: 현재 윈도우 시작 시간
     */
    private val luaScript = DefaultRedisScript<List<Any>>().apply {
        setScriptText("""
            local key = KEYS[1]
            local maxRequest = tonumber(ARGV[1])
            local windowMs = tonumber(ARGV[2])
            local nowMs = tonumber(ARGV[3])
            
            -- Fixed Window: 현재 시간을 윈도우 크기로 나눈 몫으로 윈도우 시작 시간 계산
            local windowStartMs = math.floor(nowMs / windowMs) * windowMs
            
            -- Redis Hash를 사용하여 윈도우별 상태 저장
            -- windowStart: 윈도우 시작 시간, count: 요청 수
            local currentWindowStart = redis.call('HGET', key, 'windowStart')
            local currentCount = redis.call('HGET', key, 'count')
            
            -- 새로운 윈도우인 경우 초기화
            if not currentWindowStart or tonumber(currentWindowStart) ~= windowStartMs then
                currentCount = 0
                redis.call('HSET', key, 'windowStart', windowStartMs)
                redis.call('HSET', key, 'count', 0)
            else
                currentCount = tonumber(currentCount) or 0
            end
            
            -- 요청 허용 여부 판단
            local allowed = 0
            if currentCount < maxRequest then
                allowed = 1
                currentCount = currentCount + 1
                redis.call('HSET', key, 'count', currentCount)
            end
            
            -- TTL 설정 (윈도우 크기의 2배로 여유있게 설정)
            redis.call('EXPIRE', key, windowMs / 1000 * 2)
            
            return {allowed, currentCount, windowStartMs}
        """)
        returnType = List::class.java
    }

    override fun check(userId: UserId, rule: RateLimitRule): Decision {
        return checkAt(userId, rule, Instant.now(clock))
    }

    override fun checkAt(userId: UserId, rule: RateLimitRule, now: Instant): Decision {
        val nowMs = now.toEpochMilli()
        val windowMs = rule.timeWindowSeconds * 1000L
        
        // Redis 키: "rate_limit:userId:windowSeconds"
        val key = "rate_limit:${userId.id}:${rule.timeWindowSeconds}"
        
        // Lua Script 실행
        val result = redisTemplate.execute(
            luaScript,
            listOf(key),
            rule.maxRequest,
            windowMs,
            nowMs
        ) as List<Any>
        
        val allowed = (result[0] as Number).toInt() == 1
        val currentCount = (result[1] as Number).toInt()
        val windowStartMs = (result[2] as Number).toLong()
        
        return createDecision(allowed, rule.maxRequest, currentCount, windowMs, windowStartMs, nowMs)
    }

    /**
     * Decision 객체 생성
     * 남은 요청 수와 재시도 대기 시간 계산
     */
    private fun createDecision(
        allowed: Boolean,
        maxRequest: Int,
        currentCount: Int,
        windowMs: Long,
        windowStartMs: Long,
        nowMs: Long
    ): Decision {
        val remaining = if (allowed) maxRequest - currentCount else 0
        val retryAfterMillis = if (allowed) 0L else {
            // 다음 윈도우까지 남은 시간 계산
            val nextWindowStartMs = windowStartMs + windowMs
            (nextWindowStartMs - nowMs).coerceAtLeast(0L)
        }
        
        return Decision(allowed, remaining, retryAfterMillis)
    }
}
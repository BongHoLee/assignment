package pay.assignment.ratelimit_v2

import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Clock
import java.time.Instant

/**
 * Redis 기반 Token Bucket Rate Limiter
 * 
 * 특징:
 * - Token Bucket 알고리즘을 사용하여 버스트 트래픽 허용
 * - Redis Lua Script를 통한 원자적 토큰 소모 처리
 * - 시간에 따른 토큰 자동 보충
 * - 사용자별 독립적인 버킷 관리
 * 
 * 작동 원리:
 * 1. 각 사용자는 독립적인 토큰 버킷을 가짐
 * 2. 요청이 올 때마다 버킷에서 토큰 1개 소모
 * 3. 토큰이 부족하면 요청 거부
 * 4. 시간이 지나면서 설정된 속도로 토큰 자동 보충 (최대 용량까지)
 */
class TokenBucketRateLimiter(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * Redis Lua Script를 통한 원자적 Token Bucket 구현
     * 
     * 알고리즘:
     * 1. 현재 시간과 마지막 보충 시간 차이 계산
     * 2. 시간 차이에 따라 토큰 보충 (refillRate * timeDiff)
     * 3. 버킷 용량 초과하지 않도록 제한
     * 4. 요청된 토큰 수만큼 차감 (가능한 경우)
     * 5. 버킷 상태 업데이트 및 결과 반환
     * 
     * 반환값: [allowed, tokens, nextRefillTimeMs]
     * - allowed: 요청 허용 여부 (0 또는 1)  
     * - tokens: 현재 버킷의 토큰 수
     * - nextRefillTimeMs: 다음 토큰 보충 예상 시간
     */
    private val luaScript = DefaultRedisScript<List<Any>>().apply {
        setScriptText("""
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refillTokens = tonumber(ARGV[2])
            local refillPeriodMs = tonumber(ARGV[3])
            local requestedTokens = tonumber(ARGV[4])
            local nowMs = tonumber(ARGV[5])
            
            -- 현재 버킷 상태 조회
            local bucketData = redis.call('HMGET', key, 'tokens', 'lastRefillMs')
            local currentTokens = tonumber(bucketData[1]) or capacity
            local lastRefillMs = tonumber(bucketData[2]) or nowMs
            
            -- 시간 경과에 따른 토큰 보충 계산
            local timePassed = math.max(0, nowMs - lastRefillMs)
            local tokensToAdd = math.floor(timePassed / refillPeriodMs * refillTokens)
            
            -- 토큰 보충 (용량 초과 방지)
            if tokensToAdd > 0 then
                currentTokens = math.min(capacity, currentTokens + tokensToAdd)
                lastRefillMs = nowMs
            end
            
            -- 요청 처리
            local allowed = 0
            if currentTokens >= requestedTokens then
                allowed = 1
                currentTokens = currentTokens - requestedTokens
            end
            
            -- 버킷 상태 저장
            redis.call('HMSET', key, 'tokens', currentTokens, 'lastRefillMs', lastRefillMs)
            -- TTL 설정 (용량 * 보충주기 * 2)
            redis.call('EXPIRE', key, math.ceil(capacity / refillTokens * refillPeriodMs / 1000 * 2))
            
            -- 다음 토큰 보충 시간 계산
            local nextRefillTimeMs = 0
            if not allowed and currentTokens < capacity then
                local tokensNeeded = requestedTokens - currentTokens
                nextRefillTimeMs = nowMs + math.ceil(tokensNeeded / refillTokens * refillPeriodMs)
            end
            
            return {allowed, currentTokens, nextRefillTimeMs}
        """)
        returnType = List::class.java
    }

    /**
     * Token Bucket 기반 Rate Limiting 확인
     * 
     * @param userId 사용자 ID
     * @param rule Token Bucket 규칙
     * @param requestTokens 요청할 토큰 수 (기본값 1)
     * @return Rate Limiting 결과
     */
    fun check(
        userId: String,
        rule: TokenBucketRule,
        requestTokens: Int = 1,
        now: Instant = Instant.now(clock)
    ): TokenBucketDecision {
        val nowMs = now.toEpochMilli()
        val key = "token_bucket:${userId}:${rule.capacity}_${rule.refillTokens}_${rule.refillPeriodSeconds}"
        
        val result = redisTemplate.execute(
            luaScript,
            listOf(key),
            rule.capacity,
            rule.refillTokens,
            rule.refillPeriodSeconds * 1000L,
            requestTokens,
            nowMs
        ) as List<Any>
        
        val allowed = (result[0] as Number).toInt() == 1
        val currentTokens = (result[1] as Number).toInt()
        val nextRefillTimeMs = (result[2] as Number).toLong()
        
        val retryAfterMs = if (allowed) 0L else {
            if (nextRefillTimeMs > nowMs) nextRefillTimeMs - nowMs else 0L
        }
        
        return TokenBucketDecision(
            allowed = allowed,
            currentTokens = currentTokens,
            capacity = rule.capacity,
            retryAfterMs = retryAfterMs
        )
    }
}

/**
 * Token Bucket Rule 설정
 * 
 * @param capacity 버킷 최대 용량 (토큰 개수)
 * @param refillTokens 보충 주기당 추가되는 토큰 수
 * @param refillPeriodSeconds 토큰 보충 주기 (초)
 * 
 * 예시: capacity=5, refillTokens=1, refillPeriodSeconds=1
 * -> 최대 5개 토큰, 1초마다 1개 토큰 보충 (초당 1요청 허용, 최대 5요청 버스트)
 */
data class TokenBucketRule(
    val capacity: Int,
    val refillTokens: Int,
    val refillPeriodSeconds: Int
)

/**
 * Token Bucket 처리 결과
 * 
 * @param allowed 요청 허용 여부
 * @param currentTokens 현재 버킷의 토큰 수
 * @param capacity 버킷 최대 용량
 * @param retryAfterMs 다음 요청까지 대기 시간 (밀리초)
 */
data class TokenBucketDecision(
    val allowed: Boolean,
    val currentTokens: Int,
    val capacity: Int,
    val retryAfterMs: Long
)
package pay.assignment.ratelimit_v4

import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * Spring Cloud Gateway의 RedisRateLimiter를 활용한 Rate Limiting
 * 
 * 특징:
 * - Spring Cloud Gateway에서 검증된 Token Bucket 구현
 * - Reactive Programming 지원 (WebFlux 환경)
 * - replenishRate와 burstCapacity로 세밀한 제어
 * - 프로덕션에서 검증된 안정성
 * 
 * 작동 원리:
 * 1. Token Bucket 알고리즘 기반
 * 2. replenishRate: 초당 토큰 보충 속도
 * 3. burstCapacity: 버킷 최대 용량 (버스트 허용량)
 * 4. requestedTokens: 요청당 소모 토큰 수
 */
@Component
class SpringCloudGatewayRateLimiter(
    private val reactiveRedisTemplate: ReactiveRedisTemplate<String, String>
) {
    
    private val redisRateLimiter: RedisRateLimiter
    
    init {
        // Spring Cloud Gateway의 RedisRateLimiter 인스턴스 생성
        redisRateLimiter = RedisRateLimiter(
            reactiveRedisTemplate,
            RedisScript.of(RATE_LIMITER_SCRIPT, List::class.java),
            RedisScript.of(REQUEST_RATE_LIMITER_SCRIPT, List::class.java)
        )
    }
    
    /**
     * Rate Limiting 확인
     * 
     * @param userId 사용자 ID
     * @param config Rate Limiter 설정
     * @return Rate Limiting 결과 (Reactive)
     */
    fun isAllowed(userId: String, config: GatewayRateLimitConfig): Mono<GatewayRateLimitResult> {
        val gatewayConfig = RedisRateLimiter.Config().apply {
            replenishRate = config.replenishRate
            burstCapacity = config.burstCapacity
            requestedTokens = config.requestedTokens
        }
        
        return redisRateLimiter.isAllowed("rate_limiter", userId)
            .config(gatewayConfig)
            .map { response ->
                GatewayRateLimitResult(
                    allowed = response.isAllowed,
                    tokensRemaining = response.tokensRemaining,
                    retryAfter = response.retryAfter,
                    requestedTokens = config.requestedTokens
                )
            }
    }
    
    companion object {
        /**
         * Spring Cloud Gateway에서 사용하는 Redis Lua Script
         * Token Bucket 알고리즘 구현
         */
        private const val RATE_LIMITER_SCRIPT = """
            local tokens_key = KEYS[1]
            local timestamp_key = KEYS[2]
            
            local rate = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            
            local bucket_size = math.max(capacity, requested)
            
            local tokens = tonumber(redis.call("get", tokens_key))
            if tokens == nil then
              tokens = bucket_size
            end
            
            local last_refill = tonumber(redis.call("get", timestamp_key))
            if last_refill == nil then
              last_refill = now
            end
            
            local delta = math.max(0, now-last_refill)
            local filled_tokens = math.min(capacity, tokens+(delta*rate))
            local allowed = filled_tokens >= requested
            local new_tokens = filled_tokens
            local allowed_num = 0
            if allowed then
              new_tokens = filled_tokens - requested
              allowed_num = 1
            end
            
            if ttl > 0 then
              redis.call("setex", tokens_key, ttl, new_tokens)
              redis.call("setex", timestamp_key, ttl, now)
            else
              redis.call("set", tokens_key, new_tokens)
              redis.call("set", timestamp_key, now)
            end
            
            return { allowed_num, new_tokens }
        """
        
        /**
         * 요청당 토큰 수 지원하는 개선된 스크립트
         */
        private const val REQUEST_RATE_LIMITER_SCRIPT = """
            local tokens_key = KEYS[1]
            local timestamp_key = KEYS[2]
            
            local rate = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local ttl = tonumber(ARGV[5])
            
            local bucket_size = math.max(capacity, requested)
            
            local tokens = tonumber(redis.call("get", tokens_key))
            if tokens == nil then
              tokens = bucket_size
            end
            
            local last_refill = tonumber(redis.call("get", timestamp_key))
            if last_refill == nil then
              last_refill = now
            end
            
            local delta = math.max(0, now-last_refill)
            local filled_tokens = math.min(capacity, tokens+(delta*rate))
            local allowed = filled_tokens >= requested
            local new_tokens = filled_tokens
            local allowed_num = 0
            if allowed then
              new_tokens = filled_tokens - requested
              allowed_num = 1
            end
            
            if ttl > 0 then
              redis.call("setex", tokens_key, ttl, new_tokens)
              redis.call("setex", timestamp_key, ttl, now)
            else
              redis.call("set", tokens_key, new_tokens)
              redis.call("set", timestamp_key, now)
            end
            
            return { allowed_num, new_tokens, capacity }
        """
    }
}

/**
 * Gateway Rate Limiter 설정
 * 
 * @param replenishRate 초당 토큰 보충 속도 (예: 10 = 초당 10개 토큰)
 * @param burstCapacity 버킷 최대 용량 (버스트 허용량)
 * @param requestedTokens 요청당 소모 토큰 수 (기본값 1)
 * 
 * 예시 설정:
 * - replenishRate=10, burstCapacity=20, requestedTokens=1
 *   -> 평균 10TPS, 최대 20요청 버스트 허용
 * - replenishRate=5, burstCapacity=10, requestedTokens=2  
 *   -> 평균 2.5TPS (요청당 2토큰), 최대 5요청 버스트
 */
data class GatewayRateLimitConfig(
    val replenishRate: Int,
    val burstCapacity: Int,
    val requestedTokens: Int = 1
)

/**
 * Gateway Rate Limiter 결과
 * 
 * @param allowed 요청 허용 여부
 * @param tokensRemaining 남은 토큰 수
 * @param retryAfter 재시도까지 대기 시간 (초)
 * @param requestedTokens 요청한 토큰 수
 */
data class GatewayRateLimitResult(
    val allowed: Boolean,
    val tokensRemaining: Long,
    val retryAfter: Instant?,
    val requestedTokens: Int
)
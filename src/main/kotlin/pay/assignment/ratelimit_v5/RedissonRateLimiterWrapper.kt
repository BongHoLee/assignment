package pay.assignment.ratelimit_v5

import org.redisson.api.RRateLimiter
import org.redisson.api.RateIntervalUnit
import org.redisson.api.RateType
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Redisson RRateLimiter를 활용한 Rate Limiting
 * 
 * Redisson의 특징:
 * - 분산 환경에 최적화된 Redis 클라이언트
 * - 다양한 Rate Limiting 알고리즘 내장 (Token Bucket, Leaky Bucket)
 * - 자동 클러스터 지원 및 고가용성
 * - 복잡한 분산 동기화 패턴 제공
 * 
 * RRateLimiter 특징:
 * - PER_CLIENT: 클라이언트별 독립적 제한
 * - OVERALL: 전체 클라이언트 통합 제한
 * - 다양한 시간 단위 지원 (초, 분, 시간, 일)
 */
@Component
class RedissonRateLimiterWrapper(
    private val redissonClient: RedissonClient
) {
    
    /**
     * Redisson RRateLimiter를 이용한 Rate Limiting 확인
     * 
     * @param userId 사용자 ID
     * @param config Redisson Rate Limiter 설정
     * @return Rate Limiting 결과
     */
    fun checkRateLimit(userId: String, config: RedissonRateLimitConfig): RedissonRateLimitResult {
        val limiterKey = "redisson_rate_limit:${userId}:${config.hashCode()}"
        val rateLimiter = redissonClient.getRateLimiter(limiterKey)
        
        // Rate Limiter 초기화 (필요한 경우에만)
        if (!rateLimiter.isExists) {
            rateLimiter.trySetRate(
                config.rateType,
                config.rate,
                config.rateInterval,
                config.rateIntervalUnit
            )
        }
        
        val startTime = System.currentTimeMillis()
        
        // 토큰 획득 시도
        val acquired = when (config.mode) {
            RedissonRateLimitMode.IMMEDIATE -> {
                // 즉시 확인 (대기하지 않음)
                rateLimiter.tryAcquire(config.permits)
            }
            RedissonRateLimitMode.BLOCKING -> {
                // 지정된 시간까지 대기
                rateLimiter.tryAcquire(
                    config.permits, 
                    config.maxWaitTime.toMillis(), 
                    TimeUnit.MILLISECONDS
                )
            }
            RedissonRateLimitMode.FORCE_ACQUIRE -> {
                // 무제한 대기 (주의: 데드락 가능성)
                rateLimiter.acquire(config.permits)
                true
            }
        }
        
        val endTime = System.currentTimeMillis()
        val waitTimeMs = endTime - startTime
        
        // 남은 허용량 계산
        val availablePermits = rateLimiter.availablePermits()
        
        return RedissonRateLimitResult(
            allowed = acquired,
            availablePermits = availablePermits,
            waitTimeMs = waitTimeMs,
            permits = config.permits,
            rateConfig = config
        )
    }
    
    /**
     * Rate Limiter 상태 조회
     */
    fun getRateLimiterStatus(userId: String, config: RedissonRateLimitConfig): RedissonRateLimiterStatus {
        val limiterKey = "redisson_rate_limit:${userId}:${config.hashCode()}"
        val rateLimiter = redissonClient.getRateLimiter(limiterKey)
        
        return if (rateLimiter.isExists) {
            RedissonRateLimiterStatus(
                exists = true,
                availablePermits = rateLimiter.availablePermits(),
                rateConfig = config
            )
        } else {
            RedissonRateLimiterStatus(
                exists = false,
                availablePermits = config.rate,
                rateConfig = config
            )
        }
    }
    
    /**
     * Rate Limiter 초기화
     */
    fun resetRateLimiter(userId: String, config: RedissonRateLimitConfig) {
        val limiterKey = "redisson_rate_limit:${userId}:${config.hashCode()}"
        val rateLimiter = redissonClient.getRateLimiter(limiterKey)
        rateLimiter.delete()
    }
}

/**
 * Redisson Rate Limiter 설정
 * 
 * @param rateType 제한 타입 (PER_CLIENT: 클라이언트별, OVERALL: 전체)
 * @param rate 허용 요청 수
 * @param rateInterval 시간 간격
 * @param rateIntervalUnit 시간 단위
 * @param permits 한 번에 획득할 허용량
 * @param mode 획득 모드 (즉시/대기/강제)
 * @param maxWaitTime 최대 대기 시간 (BLOCKING 모드에서만 사용)
 */
data class RedissonRateLimitConfig(
    val rateType: RateType = RateType.PER_CLIENT,
    val rate: Long,
    val rateInterval: Long,
    val rateIntervalUnit: RateIntervalUnit,
    val permits: Long = 1,
    val mode: RedissonRateLimitMode = RedissonRateLimitMode.IMMEDIATE,
    val maxWaitTime: Duration = Duration.ofSeconds(1)
)

/**
 * Redisson Rate Limit 모드
 */
enum class RedissonRateLimitMode {
    IMMEDIATE,      // 즉시 확인 (대기하지 않음)
    BLOCKING,       // 지정된 시간까지 대기
    FORCE_ACQUIRE   // 무제한 대기 (주의: 데드락 위험)
}

/**
 * Redisson Rate Limit 결과
 * 
 * @param allowed 요청 허용 여부
 * @param availablePermits 사용 가능한 허용량
 * @param waitTimeMs 실제 대기한 시간 (밀리초)
 * @param permits 요청한 허용량
 * @param rateConfig 적용된 설정
 */
data class RedissonRateLimitResult(
    val allowed: Boolean,
    val availablePermits: Long,
    val waitTimeMs: Long,
    val permits: Long,
    val rateConfig: RedissonRateLimitConfig
)

/**
 * Redisson Rate Limiter 상태
 * 
 * @param exists Rate Limiter 존재 여부
 * @param availablePermits 사용 가능한 허용량
 * @param rateConfig 설정 정보
 */
data class RedissonRateLimiterStatus(
    val exists: Boolean,
    val availablePermits: Long,
    val rateConfig: RedissonRateLimitConfig
)
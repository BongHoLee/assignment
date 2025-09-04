package pay.assignment.ratelimit_v5.annotation

import org.redisson.api.RateIntervalUnit
import org.redisson.api.RateType
import pay.assignment.ratelimit_v5.RedissonRateLimitMode
import java.util.concurrent.TimeUnit

/**
 * Redisson RRateLimiter 기반 Rate Limiting 어노테이션
 * 
 * Redisson의 강력한 분산 Rate Limiting 기능을 활용:
 * - 다양한 알고리즘 지원 (Token Bucket, Leaky Bucket)
 * - 클러스터 환경에서 완전한 분산 동기화
 * - 복잡한 Rate Limiting 시나리오 지원
 * 
 * 사용 예시:
 * @RedissonRateLimited(rate = 10, rateInterval = 1, rateIntervalUnit = RateIntervalUnit.SECONDS)
 * -> 초당 10개 요청 허용
 * 
 * @RedissonRateLimited(rate = 100, rateInterval = 1, rateIntervalUnit = RateIntervalUnit.MINUTES, permits = 5)
 * -> 분당 100개 요청 허용, 한 번에 5개씩 소모
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RedissonRateLimited(
    /**
     * Rate Limiter 타입
     * - PER_CLIENT: 각 클라이언트별로 독립적인 제한
     * - OVERALL: 모든 클라이언트가 공유하는 전체 제한
     */
    val rateType: RateType = RateType.PER_CLIENT,
    
    /**
     * 허용 요청 수
     * rateInterval 시간 동안 허용되는 최대 요청 수
     */
    val rate: Long,
    
    /**
     * 시간 간격
     * rate와 함께 사용되어 "rateInterval 동안 rate개 요청" 허용을 의미
     */
    val rateInterval: Long,
    
    /**
     * 시간 간격 단위
     * SECONDS, MINUTES, HOURS, DAYS 등 지원
     */
    val rateIntervalUnit: RateIntervalUnit = RateIntervalUnit.SECONDS,
    
    /**
     * 한 번의 요청에서 소모할 허용량
     * 일반적으로 1이지만, 리소스 집약적인 API는 더 높게 설정 가능
     */
    val permits: Long = 1,
    
    /**
     * Rate Limit 처리 모드
     * - IMMEDIATE: 즉시 확인 (대기하지 않음)
     * - BLOCKING: 지정된 시간까지 대기
     * - FORCE_ACQUIRE: 무제한 대기 (주의: 데드락 가능성)
     */
    val mode: RedissonRateLimitMode = RedissonRateLimitMode.IMMEDIATE,
    
    /**
     * 최대 대기 시간 (초)
     * BLOCKING 모드에서만 사용됨
     */
    val maxWaitTimeSeconds: Long = 1
)
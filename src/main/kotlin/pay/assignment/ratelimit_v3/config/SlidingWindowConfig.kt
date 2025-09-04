package pay.assignment.ratelimit_v3.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.RedisTemplate
import pay.assignment.ratelimit_v3.SlidingWindowRateLimiter

/**
 * Sliding Window Rate Limiter 설정
 * 
 * Sliding Window 방식의 특징:
 * - 정확한 시간 윈도우 내에서 요청 수 제한
 * - Fixed Window의 경계 효과 문제 해결
 * - Redis Sorted Set을 활용한 시간 기반 요청 추적
 * - 높은 정확성을 제공하지만 상대적으로 많은 메모리 사용
 */
@Configuration
class SlidingWindowConfig {

    /**
     * Sliding Window Rate Limiter Bean 등록
     * 
     * @param redisTemplate Redis 연결을 위한 템플릿
     * @return Sliding Window 기반 Rate Limiter
     */
    @Bean
    fun slidingWindowRateLimiter(redisTemplate: RedisTemplate<String, Any>): SlidingWindowRateLimiter {
        return SlidingWindowRateLimiter(redisTemplate)
    }
}
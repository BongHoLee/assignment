package pay.assignment.ratelimit_v1.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.RedisTemplate
import pay.assignment.ratelimit_v1.RateLimiter
import pay.assignment.ratelimit_v1.RedisRateLimiter

/**
 * Rate Limiter 설정 클래스
 * 
 * Redis 기반 Rate Limiter를 Spring Bean으로 등록하고 설정을 관리
 * 
 * 필요 조건:
 * - RedisTemplate이 이미 설정되어 있어야 함 (application.yml에서 Redis 연결 설정)
 * - Spring Data Redis 의존성이 추가되어 있어야 함
 */
@Configuration
class RateLimiterConfig {

    /**
     * Redis 기반 Rate Limiter Bean 등록
     * 
     * @param redisTemplate Spring에서 자동으로 생성된 RedisTemplate
     * @return RateLimiter 구현체 (RedisRateLimiter)
     */
    @Bean
    fun rateLimiter(redisTemplate: RedisTemplate<String, Any>): RateLimiter {
        return RedisRateLimiter(redisTemplate)
    }
}
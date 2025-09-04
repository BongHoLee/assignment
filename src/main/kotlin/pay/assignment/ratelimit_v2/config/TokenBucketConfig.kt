package pay.assignment.ratelimit_v2.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.RedisTemplate
import pay.assignment.ratelimit_v2.TokenBucketRateLimiter

/**
 * Token Bucket Rate Limiter 설정
 * 
 * Token Bucket 방식의 특징:
 * - 버스트 트래픽을 허용하면서도 평균 처리율 제한
 * - 토큰이 시간에 따라 축적되어 일시적 대기 후 요청 처리 가능
 * - 사용자별 독립적인 토큰 버킷 관리
 */
@Configuration
class TokenBucketConfig {

    /**
     * Token Bucket Rate Limiter Bean 등록
     * 
     * @param redisTemplate Redis 연결을 위한 템플릿
     * @return Token Bucket 기반 Rate Limiter
     */
    @Bean
    fun tokenBucketRateLimiter(redisTemplate: RedisTemplate<String, Any>): TokenBucketRateLimiter {
        return TokenBucketRateLimiter(redisTemplate)
    }
}
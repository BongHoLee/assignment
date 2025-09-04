package pay.assignment.ratelimit_v4.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer
import pay.assignment.ratelimit_v4.SpringCloudGatewayRateLimiter

/**
 * Spring Cloud Gateway 스타일 Rate Limiter 설정
 * 
 * Spring Cloud Gateway의 특징:
 * - Reactive Programming (WebFlux) 기반
 * - RedisRateLimiter의 검증된 Token Bucket 구현
 * - replenishRate와 burstCapacity 분리 제어
 * - 대규모 트래픽에서 검증된 성능
 * 
 * 주의사항:
 * - 실제 Gateway는 WebFlux 환경에서 동작
 * - 이 구현은 Spring MVC 환경에서 Gateway 방식을 시뮬레이션
 */
@Configuration
class GatewayRateLimitConfig {

    /**
     * Reactive Redis Template 설정
     * Spring Cloud Gateway의 RedisRateLimiter에서 사용
     */
    @Bean
    fun reactiveRedisTemplate(factory: RedisConnectionFactory): ReactiveRedisTemplate<String, String> {
        val serializationContext = RedisSerializationContext.newSerializationContext<String, String>()
            .key(StringRedisSerializer())
            .value(StringRedisSerializer())
            .build()
        
        return ReactiveRedisTemplate(factory, serializationContext)
    }

    /**
     * Spring Cloud Gateway Rate Limiter Bean 등록
     * 
     * @param reactiveRedisTemplate Reactive Redis 연결
     * @return Spring Cloud Gateway 스타일 Rate Limiter
     */
    @Bean
    fun springCloudGatewayRateLimiter(
        reactiveRedisTemplate: ReactiveRedisTemplate<String, String>
    ): SpringCloudGatewayRateLimiter {
        return SpringCloudGatewayRateLimiter(reactiveRedisTemplate)
    }
}
package pay.assignment.ratelimit_v6.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import pay.assignment.ratelimit_v6.Bucket4jRateLimiter

/**
 * Bucket4j + Redis Rate Limiter 설정
 * 
 * Bucket4j의 특징:
 * - Java Rate Limiting 라이브러리의 표준
 * - Token Bucket 알고리즘의 완벽한 구현
 * - 다양한 분산 백엔드 지원 (Redis, Hazelcast, Ignite 등)
 * - 동기/비동기 API 모두 제공
 * - Spring Boot Starter를 통한 완벽한 통합
 * 
 * Redis 백엔드의 장점:
 * - 분산 환경에서 완벽한 동기화
 * - Lettuce 기반 고성능 연결
 * - 자동 TTL 관리
 * - 클러스터/센티널 지원
 */
@Configuration
class Bucket4jConfig {

    /**
     * Bucket4j Rate Limiter Bean 등록
     * 
     * Lettuce 기반 Redis 연결을 사용하여 분산 토큰 버킷 구현
     * 
     * @param lettuceConnectionFactory Lettuce Redis 연결 팩토리
     * @return Bucket4j Rate Limiter
     */
    @Bean
    fun bucket4jRateLimiter(lettuceConnectionFactory: LettuceConnectionFactory): Bucket4jRateLimiter {
        return Bucket4jRateLimiter(lettuceConnectionFactory)
    }
}
package pay.assignment.ratelimit_v5.config

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pay.assignment.ratelimit_v5.RedissonRateLimiterWrapper

/**
 * Redisson RRateLimiter 설정
 * 
 * Redisson의 특징:
 * - 분산 환경에 최적화된 Redis 클라이언트
 * - 자동 클러스터/센티널 지원
 * - 다양한 분산 자료구조 제공
 * - 고성능 연결 관리 및 최적화
 * 
 * RRateLimiter 장점:
 * - 여러 알고리즘 지원 (Token Bucket, Leaky Bucket)
 * - 완벽한 분산 동기화
 * - 자동 TTL 관리
 * - 복잡한 Rate Limiting 시나리오 지원
 */
@Configuration
class RedissonRateLimitConfig {

    /**
     * Redisson Client 설정
     * 
     * 실제 환경에서는 다음과 같은 설정 고려:
     * - 클러스터 설정 (Cluster/Sentinel)
     * - 연결 풀 크기 최적화
     * - 재시도 및 타임아웃 설정
     * - 직렬화 방식 선택
     */
    @Bean
    fun redissonClient(redisProperties: RedisProperties): RedissonClient {
        val config = Config()
        
        // 단일 서버 설정 (개발/테스트 환경)
        config.useSingleServer()
            .setAddress("redis://${redisProperties.host}:${redisProperties.port}")
            .setDatabase(redisProperties.database)
            .apply {
                // Redis 비밀번호가 설정된 경우
                redisProperties.password?.let { password ->
                    setPassword(password)
                }
                
                // 연결 설정 최적화
                setConnectionMinimumIdleSize(5)
                setConnectionPoolSize(20)
                setConnectTimeout(3000)
                setTimeout(2000)
                setRetryAttempts(3)
                setRetryInterval(1500)
            }
        
        /* 클러스터 환경 예시 (실제 환경에서 사용)
        config.useClusterServers()
            .setNodeAddresses(
                "redis://redis-cluster-1:6379",
                "redis://redis-cluster-2:6379", 
                "redis://redis-cluster-3:6379"
            )
            .setMasterConnectionMinimumIdleSize(5)
            .setMasterConnectionPoolSize(20)
            .setSlaveConnectionMinimumIdleSize(5)
            .setSlaveConnectionPoolSize(20)
        */
        
        /* Sentinel 환경 예시 (고가용성)
        config.useSentinelServers()
            .setMasterName("mymaster")
            .setSentinelAddresses(
                "redis://sentinel-1:26379",
                "redis://sentinel-2:26379",
                "redis://sentinel-3:26379"
            )
        */
        
        return Redisson.create(config)
    }

    /**
     * Redisson Rate Limiter Wrapper Bean 등록
     * 
     * @param redissonClient Redisson 클라이언트
     * @return Redisson Rate Limiter 래퍼
     */
    @Bean
    fun redissonRateLimiterWrapper(redissonClient: RedissonClient): RedissonRateLimiterWrapper {
        return RedissonRateLimiterWrapper(redissonClient)
    }
}
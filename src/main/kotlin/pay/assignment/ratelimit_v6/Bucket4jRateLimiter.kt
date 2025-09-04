package pay.assignment.ratelimit_v6

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Bucket4j + Redis를 활용한 분산 Rate Limiter
 * 
 * Bucket4j의 특징:
 * - Java용 Rate Limiting 라이브러리의 표준
 * - Token Bucket 알고리즘의 완벽한 구현
 * - 다양한 백엔드 지원 (Redis, Hazelcast, Ignite 등)
 * - 동기/비동기 API 모두 지원
 * - Spring Boot 완벽 통합
 * 
 * Redis 백엔드의 장점:
 * - 분산 환경에서 완벽한 동기화
 * - 고성능 Redis 기반 연산
 * - 자동 TTL 관리
 * - 클러스터 환경 지원
 */
@Component
class Bucket4jRateLimiter(
    private val lettuceConnectionFactory: LettuceConnectionFactory
) {
    
    private val proxyManager: LettuceBasedProxyManager<String>
    
    init {
        // Lettuce 기반 Redis Proxy Manager 초기화
        proxyManager = LettuceBasedProxyManager.builderFor(lettuceConnectionFactory)
            .build()
    }
    
    /**
     * Rate Limiting 확인 (동기 방식)
     * 
     * @param userId 사용자 ID
     * @param config Bucket4j 설정
     * @return Rate Limiting 결과
     */
    fun checkRateLimit(userId: String, config: Bucket4jConfig): Bucket4jResult {
        val bucketKey = "bucket4j:${userId}:${config.hashCode()}"
        val bucket = getBucket(bucketKey, config)
        
        val startTime = System.currentTimeMillis()
        
        // 토큰 소모 시도
        val probe = bucket.tryConsumeAndReturnRemaining(config.tokensToConsume)
        
        val endTime = System.currentTimeMillis()
        val processingTime = endTime - startTime
        
        return Bucket4jResult(
            allowed = probe.isConsumed,
            remainingTokens = probe.remainingTokens,
            nanosToWaitForRefill = probe.nanosToWaitForRefill,
            tokensConsumed = if (probe.isConsumed) config.tokensToConsume else 0,
            processingTimeMs = processingTime,
            config = config
        )
    }
    
    /**
     * Rate Limiting 확인 (비동기 방식)
     * 
     * @param userId 사용자 ID
     * @param config Bucket4j 설정
     * @return Rate Limiting 결과 (CompletableFuture)
     */
    fun checkRateLimitAsync(userId: String, config: Bucket4jConfig): CompletableFuture<Bucket4jResult> {
        val bucketKey = "bucket4j:${userId}:${config.hashCode()}"
        val bucket = getBucket(bucketKey, config)
        
        val startTime = System.currentTimeMillis()
        
        return bucket.asAsync().tryConsumeAndReturnRemaining(config.tokensToConsume)
            .thenApply { probe ->
                val endTime = System.currentTimeMillis()
                val processingTime = endTime - startTime
                
                Bucket4jResult(
                    allowed = probe.isConsumed,
                    remainingTokens = probe.remainingTokens,
                    nanosToWaitForRefill = probe.nanosToWaitForRefill,
                    tokensConsumed = if (probe.isConsumed) config.tokensToConsume else 0,
                    processingTimeMs = processingTime,
                    config = config
                )
            }
    }
    
    /**
     * 버킷 상태 조회
     */
    fun getBucketStatus(userId: String, config: Bucket4jConfig): Bucket4jStatus {
        val bucketKey = "bucket4j:${userId}:${config.hashCode()}"
        val bucket = getBucket(bucketKey, config)
        
        val availableTokens = bucket.availableTokens
        val capacity = bucket.configuration.bandwidths[0].capacity
        
        return Bucket4jStatus(
            availableTokens = availableTokens,
            capacity = capacity,
            config = config
        )
    }
    
    /**
     * 버킷 인스턴스 획득 (설정에 따라 생성 또는 조회)
     */
    private fun getBucket(bucketKey: String, config: Bucket4jConfig): Bucket {
        val bucketConfiguration = createBucketConfiguration(config)
        
        return proxyManager.builder()
            .build(bucketKey, bucketConfiguration)
    }
    
    /**
     * Bucket4j 설정을 기반으로 BucketConfiguration 생성
     */
    private fun createBucketConfiguration(config: Bucket4jConfig): BucketConfiguration {
        val builder = BucketConfiguration.builder()
        
        // 각 Bandwidth 설정 추가
        config.bandwidths.forEach { bandwidthConfig ->
            val bandwidth = when (bandwidthConfig.type) {
                Bucket4jBandwidthType.SIMPLE -> {
                    Bandwidth.simple(bandwidthConfig.capacity, bandwidthConfig.period)
                }
                Bucket4jBandwidthType.CLASSIC -> {
                    Bandwidth.classic(
                        bandwidthConfig.capacity,
                        bandwidthConfig.refillTokens,
                        bandwidthConfig.period
                    )
                }
            }
            
            builder.addLimit(bandwidth)
        }
        
        return builder.build()
    }
}

/**
 * Bucket4j 설정
 * 
 * @param bandwidths 대역폭 설정 목록 (여러 개 제한을 조합 가능)
 * @param tokensToConsume 한 번의 요청에서 소모할 토큰 수
 */
data class Bucket4jConfig(
    val bandwidths: List<Bucket4jBandwidthConfig>,
    val tokensToConsume: Long = 1
)

/**
 * Bucket4j 대역폭 설정
 * 
 * @param type 대역폭 타입 (SIMPLE/CLASSIC)
 * @param capacity 버킷 용량
 * @param refillTokens 보충 토큰 수 (CLASSIC 타입에서만 사용)
 * @param period 시간 주기
 */
data class Bucket4jBandwidthConfig(
    val type: Bucket4jBandwidthType,
    val capacity: Long,
    val refillTokens: Long = capacity,
    val period: Duration
)

/**
 * Bucket4j 대역폭 타입
 */
enum class Bucket4jBandwidthType {
    /**
     * Simple Bandwidth: capacity개 토큰을 period 시간마다 완전히 리필
     * 예: simple(10, Duration.ofSeconds(1)) = 1초마다 10개 토큰으로 완전 리필
     */
    SIMPLE,
    
    /**
     * Classic Bandwidth: period 시간마다 refillTokens개씩 점진적 리필
     * 예: classic(10, 2, Duration.ofSeconds(1)) = 1초마다 2개씩 토큰 추가 (최대 10개)
     */
    CLASSIC
}

/**
 * Bucket4j Rate Limiting 결과
 * 
 * @param allowed 요청 허용 여부
 * @param remainingTokens 남은 토큰 수
 * @param nanosToWaitForRefill 다음 토큰 보충까지 대기 시간 (나노초)
 * @param tokensConsumed 실제 소모된 토큰 수
 * @param processingTimeMs 처리 시간 (밀리초)
 * @param config 적용된 설정
 */
data class Bucket4jResult(
    val allowed: Boolean,
    val remainingTokens: Long,
    val nanosToWaitForRefill: Long,
    val tokensConsumed: Long,
    val processingTimeMs: Long,
    val config: Bucket4jConfig
) {
    /**
     * 재시도까지 대기 시간 (밀리초)
     */
    val retryAfterMs: Long
        get() = nanosToWaitForRefill / 1_000_000
}

/**
 * Bucket4j 버킷 상태
 * 
 * @param availableTokens 사용 가능한 토큰 수
 * @param capacity 버킷 총 용량
 * @param config 버킷 설정
 */
data class Bucket4jStatus(
    val availableTokens: Long,
    val capacity: Long,
    val config: Bucket4jConfig
)
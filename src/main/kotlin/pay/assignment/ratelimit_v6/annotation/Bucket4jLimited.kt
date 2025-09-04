package pay.assignment.ratelimit_v6.annotation

import pay.assignment.ratelimit_v6.Bucket4jBandwidthType
import java.util.concurrent.TimeUnit

/**
 * Bucket4j 기반 Rate Limiting 어노테이션
 * 
 * Bucket4j는 Java 생태계에서 가장 완성도 높은 Rate Limiting 라이브러리로
 * Token Bucket 알고리즘의 표준 구현을 제공합니다.
 * 
 * 특징:
 * - 다중 대역폭 지원 (여러 제한을 동시 적용 가능)
 * - Simple/Classic Bandwidth 타입 지원
 * - 다양한 백엔드 (Redis, Hazelcast, Ignite 등)
 * - 동기/비동기 API
 * 
 * 사용 예시:
 * @Bucket4jLimited(capacity = 10, period = 1, timeUnit = TimeUnit.SECONDS)
 * -> 1초당 10개 토큰 (Simple Bandwidth)
 * 
 * @Bucket4jLimited(
 *   type = Bucket4jBandwidthType.CLASSIC,
 *   capacity = 100, 
 *   refillTokens = 10,
 *   period = 1, 
 *   timeUnit = TimeUnit.SECONDS
 * )
 * -> 용량 100, 1초마다 10개씩 점진적 보충 (Classic Bandwidth)
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Bucket4jLimited(
    /**
     * 대역폭 타입
     * - SIMPLE: period마다 capacity로 완전히 리필
     * - CLASSIC: period마다 refillTokens개씩 점진적 리필
     */
    val type: Bucket4jBandwidthType = Bucket4jBandwidthType.SIMPLE,
    
    /**
     * 토큰 버킷 용량 (최대 토큰 수)
     */
    val capacity: Long,
    
    /**
     * 보충 토큰 수 (CLASSIC 타입에서만 사용)
     * SIMPLE 타입에서는 무시되고 capacity와 동일하게 처리
     */
    val refillTokens: Long = -1, // -1이면 capacity와 동일하게 설정
    
    /**
     * 시간 주기
     */
    val period: Long,
    
    /**
     * 시간 단위
     */
    val timeUnit: TimeUnit = TimeUnit.SECONDS,
    
    /**
     * 요청당 소모할 토큰 수
     */
    val tokensToConsume: Long = 1
)
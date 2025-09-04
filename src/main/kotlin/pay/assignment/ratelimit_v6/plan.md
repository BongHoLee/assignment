# Bucket4j + Redis 구현 계획서

## 1. 개요

### 1.1 Bucket4j란?
Bucket4j는 **Java 생태계의 Rate Limiting 표준 라이브러리**입니다. Token Bucket 알고리즘의 가장 완성도 높은 구현체로, 다양한 분산 백엔드를 지원하며 Spring Boot와 완벽하게 통합됩니다.

### 1.2 Bucket4j + Redis의 특징
- **표준 구현**: Java Rate Limiting의 사실상 표준
- **완벽한 Token Bucket**: 알고리즘의 교과서적 구현
- **다양한 백엔드**: Redis, Hazelcast, Ignite, JCache 지원
- **동기/비동기**: 모든 방식의 API 제공
- **Spring 통합**: Spring Boot Starter 완벽 지원

## 2. 아키텍처 설계

### 2.1 Bucket4j 전체 아키텍처
```
┌─────────────────────────────────────────────────────────┐
│                  Application Layer                      │
│  ┌─────────────────────────────────────────────────┐    │
│  │                Bucket4j API                     │    │
│  │  ┌─────────────────────────────────────────┐    │    │
│  │  │            Bucket                       │    │    │
│  │  │  • tryConsume(tokens)                   │    │    │
│  │  │  • tryConsumeAndReturnRemaining()       │    │    │
│  │  │  • availableTokens()                    │    │    │
│  │  │  • addTokens(tokens)                    │    │    │
│  │  └─────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│            Bucket4j Redis Integration Layer             │
│  • LettuceBasedProxyManager                             │
│  • CAS (Compare-And-Swap) Operations                   │
│  • Bandwidth Configuration Management                   │
│  • Automatic TTL Handling                              │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│                 Redis Lettuce Client                   │
│  • High Performance Async I/O                          │
│  • Connection Pooling                                  │
│  • Cluster/Sentinel Support                            │
│  • Reactive Streams Support                            │
└─────────────────────────────────────────────────────────┘
```

### 2.2 핵심 개념 정리

#### 2.2.1 Bandwidth 개념
```kotlin
// Simple Bandwidth: period마다 capacity로 완전히 리필
Bandwidth.simple(10, Duration.ofSeconds(1))
// 1초마다 10개 토큰으로 완전 보충

// Classic Bandwidth: period마다 refillTokens개씩 점진적 리필  
Bandwidth.classic(100, 10, Duration.ofSeconds(1))
// 용량 100개, 1초마다 10개씩 점진적 보충
```

#### 2.2.2 다중 Bandwidth 지원
```kotlin
val bucketConfig = BucketConfiguration.builder()
    .addLimit(Bandwidth.simple(10, Duration.ofSeconds(1)))    // 초당 10개
    .addLimit(Bandwidth.simple(100, Duration.ofMinutes(1)))   // 분당 100개
    .addLimit(Bandwidth.simple(1000, Duration.ofHours(1)))    // 시간당 1000개
    .build()
// 모든 제한을 동시에 만족해야 함
```

## 3. 알고리즘 분석: Bucket4j Token Bucket

### 3.1 Simple vs Classic Bandwidth
```kotlin
// Simple Bandwidth 동작
time: 0s -> tokens: 10 (초기)
time: 1s -> tokens: 10 (완전 리필)
time: 1.5s -> tokens: 10 (중간에는 변화 없음)

// Classic Bandwidth 동작  
time: 0s -> tokens: 100 (초기)
time: 0.1s -> tokens: 101 (점진적 보충: 0.1초 * 10개/초 = 1개)
time: 0.5s -> tokens: 105 (점진적 보충: 0.5초 * 10개/초 = 5개)
```

### 3.2 CAS (Compare-And-Swap) 메커니즘
```java
// Bucket4j의 분산 동기화 방식 (의사 코드)
do {
    currentState = redis.get(bucketKey)
    newState = calculateNewState(currentState, tokensRequested)
    success = redis.compareAndSwap(bucketKey, currentState, newState)
} while (!success)
```

### 3.3 Lettuce 기반 비동기 처리
```kotlin
// 동기 API
val probe = bucket.tryConsumeAndReturnRemaining(tokens)

// 비동기 API  
val futureProbe = bucket.asAsync().tryConsumeAndReturnRemaining(tokens)
futureProbe.thenApply { probe -> 
    // 비블로킹 처리
}
```

## 4. 구현 세부사항

### 4.1 LettuceBasedProxyManager 설정
```kotlin
val proxyManager = LettuceBasedProxyManager.builderFor(lettuceConnectionFactory)
    .withExpirationAfterWriteStrategy(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
        Duration.ofMinutes(1) // 버킷 TTL 설정
    ))
    .build()
```

### 4.2 동적 버킷 생성
```kotlin
fun getBucket(userId: String, config: Bucket4jConfig): Bucket {
    val bucketKey = "bucket4j:$userId:${config.hashCode()}"
    val bucketConfiguration = createBucketConfiguration(config)
    
    return proxyManager.builder()
        .build(bucketKey, bucketConfiguration)
}
```

### 4.3 Bandwidth 설정 전략
```kotlin
fun createBandwidthConfiguration(config: Bucket4jBandwidthConfig): Bandwidth {
    return when (config.type) {
        Bucket4jBandwidthType.SIMPLE -> {
            Bandwidth.simple(config.capacity, config.period)
        }
        Bucket4jBandwidthType.CLASSIC -> {
            Bandwidth.classic(
                config.capacity,    // 최대 용량
                config.refillTokens, // 보충 토큰 수
                config.period       // 보충 주기
            )
        }
    }
}
```

## 5. 사용 시나리오별 최적 설정

### 5.1 API Gateway 패턴
```kotlin
@Bucket4jLimited(
    type = Bucket4jBandwidthType.CLASSIC,
    capacity = 100,      // 버스트 용량
    refillTokens = 10,   // 점진적 보충
    period = 1,
    timeUnit = TimeUnit.SECONDS
)
// 평균 10 TPS, 버스트 100 요청 허용
```

### 5.2 배치 처리 최적화
```kotlin
@Bucket4jLimited(
    type = Bucket4jBandwidthType.SIMPLE,
    capacity = 1000,     // 대용량 버킷
    period = 1,
    timeUnit = TimeUnit.MINUTES
)
// 1분에 1000개, 배치 처리에 최적
```

### 5.3 계층적 제한 구조
```kotlin
// 어노테이션으로는 단일 Bandwidth만 지원
// 복잡한 시나리오는 직접 구성 필요
val bucket = proxyManager.builder().build(key,
    BucketConfiguration.builder()
        .addLimit(Bandwidth.simple(10, Duration.ofSeconds(1)))    // 초당 제한
        .addLimit(Bandwidth.simple(100, Duration.ofMinutes(1)))   // 분당 제한
        .addLimit(Bandwidth.simple(1000, Duration.ofHours(1)))    // 시간당 제한
        .build()
)
```

### 5.4 동적 토큰 관리
```kotlin
// 토큰 추가 (프리미엄 사용자)
bucket.addTokens(100)

// 토큰 강제 소모 (페널티)
bucket.tryConsume(50)

// 상태 조회
val availableTokens = bucket.availableTokens
```

## 6. 성능 특성

### 6.1 Bucket4j 최적화 기술
- **Lazy Evaluation**: 필요시에만 계산 수행
- **CAS 최적화**: Lock-free 알고리즘으로 동시성 최대화
- **메모리 효율성**: 최소한의 메타데이터만 저장
- **TTL 자동 관리**: 사용되지 않는 버킷 자동 정리

### 6.2 Redis Lettuce 최적화
```kotlin
// 연결 풀 설정
lettuce.pool.max-active=20
lettuce.pool.max-wait=-1ms
lettuce.pool.max-idle=8
lettuce.pool.min-idle=0

// 타임아웃 설정
lettuce.timeout=2000ms
```

### 6.3 성능 벤치마크 (참고)
- **처리량**: 초당 100,000+ 요청 처리 가능
- **지연시간**: 평균 1ms 미만 (Redis 지연시간 + α)
- **메모리**: 버킷당 약 100 bytes

## 7. 모니터링 및 운영

### 7.1 Bucket4j 메트릭
```kotlin
// 버킷 상태 모니터링
val status = Bucket4jStatus(
    availableTokens = bucket.availableTokens,
    capacity = bucket.configuration.bandwidths[0].capacity,
    config = config
)
```

### 7.2 Spring Boot Actuator 통합
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,bucket4j
  endpoint:
    bucket4j:
      enabled: true
```

### 7.3 모니터링 지표
- **토큰 소모율**: 시간당 소모된 토큰 수
- **버킷 활용률**: capacity 대비 사용률
- **거부율**: 토큰 부족으로 거부된 요청 비율
- **Redis 성능**: 연결, 응답 시간, 메모리

## 8. 고급 기능 활용

### 8.1 스케줄링 기반 토큰 관리
```kotlin
@Scheduled(fixedRate = 60000) // 1분마다
fun refillPremiumUsers() {
    premiumUsers.forEach { userId ->
        val bucket = getBucket(userId, premiumConfig)
        bucket.addTokens(100) // 프리미엄 보너스 토큰
    }
}
```

### 8.2 조건부 토큰 소모
```kotlin
// 조건부 소모 (비즈니스 로직 기반)
val tokensToConsume = when (userTier) {
    "PREMIUM" -> 1
    "STANDARD" -> 2  
    "BASIC" -> 3
}

val probe = bucket.tryConsumeAndReturnRemaining(tokensToConsume)
```

### 8.3 이벤트 기반 토큰 관리
```kotlin
@EventListener
fun handleUserUpgrade(event: UserUpgradeEvent) {
    val bucket = getBucket(event.userId, upgradedConfig)
    bucket.addTokens(500) // 업그레이드 보너스
}
```

## 9. Spring Boot 완벽 통합

### 9.1 Auto Configuration
```yaml
# application.yml
bucket4j:
  enabled: true
  cache-to-use: redis
  filters:
    - cache-name: buckets
      url: /*
      rate-limits:
        - bandwidths:
          - capacity: 10
            time: 1
            unit: seconds
```

### 9.2 메트릭 자동 수집
```kotlin
@RestController
class MetricsController {
    
    @Autowired
    private lateinit var meterRegistry: MeterRegistry
    
    @GetMapping("/metrics/bucket4j")
    fun getBucket4jMetrics(): Map<String, Double> {
        return mapOf(
            "bucket4j.consumed" to meterRegistry.counter("bucket4j.consumed").count(),
            "bucket4j.rejected" to meterRegistry.counter("bucket4j.rejected").count()
        )
    }
}
```

## 10. 장단점 분석

### 10.1 장점
- ✅ **표준 라이브러리**: Java Rate Limiting의 사실상 표준
- ✅ **완벽한 구현**: Token Bucket 알고리즘의 교과서적 구현
- ✅ **다양한 백엔드**: Redis, Hazelcast, Ignite 등 지원
- ✅ **동기/비동기**: 모든 방식의 API 제공
- ✅ **Spring 통합**: Boot Starter로 완벽 통합
- ✅ **고성능**: Lock-free CAS 알고리즘
- ✅ **유연성**: 다중 Bandwidth, 동적 설정 지원

### 10.2 단점
- ❌ **학습 곡선**: 개념 이해 필요 (Bandwidth, CAS 등)
- ❌ **의존성**: 추가 라이브러리 의존성
- ❌ **복잡성**: 단순한 용도에는 과도할 수 있음
- ❌ **디버깅**: 분산 환경에서 디버깅 어려움

## 11. 실제 사용 사례

### 11.1 전자상거래 플랫폼
- **상품 검색**: 사용자별 검색 쿼리 제한
- **주문 처리**: 동시 주문 건수 제한
- **리뷰 등록**: 스팸 방지를 위한 제한

### 11.2 금융 서비스  
- **거래 요청**: 사용자별 거래 빈도 제한
- **API 호출**: 외부 시스템 호출 제한
- **인증 시도**: 로그인 시도 횟수 제한

### 11.3 소셜 미디어
- **포스팅**: 사용자별 포스팅 빈도 제한
- **팔로우**: 스팸 팔로우 방지
- **댓글**: 댓글 도배 방지

## 12. 면접 평가 포인트

### 12.1 핵심 질문
1. "Simple Bandwidth와 Classic Bandwidth의 차이는 무엇인가요?"
2. "Bucket4j가 분산 환경에서 일관성을 보장하는 방법은?"
3. "언제 Bucket4j를 선택하고 언제 다른 방법을 선택하나요?"

### 12.2 실무 관점 질문
1. "대규모 트래픽에서 Bucket4j의 성능 최적화 방법은?"
2. "Redis 장애시 Bucket4j는 어떻게 동작하나요?"
3. "다중 Bandwidth 설정의 실제 사용 사례는?"

이 Bucket4j 구현은 **Java 생태계의 표준**이라는 안정성과 **완벽한 Token Bucket 구현**이라는 완성도를 제공하지만, **학습 곡선**과 **추가 복잡성**이라는 트레이드오프를 가진 프로페셔널 솔루션입니다.
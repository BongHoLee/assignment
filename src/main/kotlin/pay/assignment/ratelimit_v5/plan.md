# Redisson RRateLimiter 구현 계획서

## 1. 개요

### 1.1 Redisson이란?
Redisson은 **Java용 Redis 클라이언트의 완성형**으로, 분산 환경에 최적화된 다양한 자료구조와 동기화 패턴을 제공합니다. RRateLimiter는 이 중에서도 가장 정교한 분산 Rate Limiting 구현체입니다.

### 1.2 RRateLimiter의 특징
- **완벽한 분산 동기화**: Redis 클러스터에서 100% 일관성 보장
- **다양한 알고리즘**: Token Bucket, Leaky Bucket 모두 지원
- **유연한 제어**: PER_CLIENT vs OVERALL 모드
- **자동 관리**: TTL, 클린업, 장애 복구 모두 자동화

## 2. 아키텍처 설계

### 2.1 Redisson 전체 아키텍처
```
┌─────────────────────────────────────────────────────────┐
│                  Application Layer                      │
│  ┌─────────────────────────────────────────────────┐    │
│  │              RRateLimiter                       │    │
│  │  ┌─────────────────────────────────────────┐    │    │
│  │  │           Token Bucket                  │    │    │
│  │  │  • acquire(permits)                     │    │    │
│  │  │  • tryAcquire(permits, timeout)        │    │    │
│  │  │  • availablePermits()                  │    │    │
│  │  └─────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│                Redisson Client Layer                    │
│  • Connection Pool Management                           │
│  • Lua Script Execution                                │
│  • Cluster/Sentinel Support                            │
│  • Automatic Failover                                  │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│                Redis Cluster/Sentinel                  │
│  • Distributed Token Buckets                           │
│  • High Availability                                   │
│  • Automatic Sharding                                  │
└─────────────────────────────────────────────────────────┘
```

### 2.2 RRateLimiter 핵심 개념
- **RateType.PER_CLIENT**: 각 클라이언트별 독립적 제한
- **RateType.OVERALL**: 모든 클라이언트 통합 제한
- **Rate Interval**: 시간 단위 (SECONDS, MINUTES, HOURS, DAYS)
- **Permits**: 허가/토큰 개념

## 3. 알고리즘 분석: Redisson Token Bucket

### 3.1 PER_CLIENT vs OVERALL 차이
```kotlin
// PER_CLIENT: 각 인스턴스별 독립적
rateLimiter.trySetRate(RateType.PER_CLIENT, 10, 1, RateIntervalUnit.SECONDS)
// 각 애플리케이션 인스턴스마다 초당 10개

// OVERALL: 전체 인스턴스 공유
rateLimiter.trySetRate(RateType.OVERALL, 10, 1, RateIntervalUnit.SECONDS)  
// 모든 애플리케이션 인스턴스를 합쳐서 초당 10개
```

### 3.2 동작 모드별 특징

#### 3.2.1 IMMEDIATE Mode
```kotlin
val acquired = rateLimiter.tryAcquire(permits)
// 즉시 반환: 토큰이 있으면 true, 없으면 false
```

#### 3.2.2 BLOCKING Mode
```kotlin
val acquired = rateLimiter.tryAcquire(permits, maxWaitTime, TimeUnit.SECONDS)
// 지정된 시간까지 대기: 토큰 보충을 기다림
```

#### 3.2.3 FORCE_ACQUIRE Mode
```kotlin
rateLimiter.acquire(permits)
// 무제한 대기: 토큰을 얻을 때까지 블로킹 (데드락 주의!)
```

### 3.3 Redisson 내부 최적화
```java
// Redisson 내부의 정교한 Lua 스크립트 (의사 코드)
local rate = ARGV[1]
local interval = ARGV[2] 
local permits = ARGV[3]

// Token Bucket 알고리즘 구현
local tokens = redis.call('GET', tokens_key) or rate
local last_refill = redis.call('GET', timestamp_key) or now
local elapsed = now - last_refill
local tokens_to_add = math.floor(elapsed / interval * rate)
local new_tokens = math.min(rate, tokens + tokens_to_add)

if new_tokens >= permits then
    redis.call('SET', tokens_key, new_tokens - permits)
    redis.call('SET', timestamp_key, now)
    return 1
else
    return 0
end
```

## 4. 구현 세부사항

### 4.1 RedissonClient 설정 최적화
```kotlin
val config = Config()
config.useSingleServer()
    .setAddress("redis://localhost:6379")
    .setConnectionMinimumIdleSize(5)    // 최소 유지 연결
    .setConnectionPoolSize(20)          // 최대 연결 수
    .setConnectTimeout(3000)            // 연결 타임아웃
    .setTimeout(2000)                   // 명령 타임아웃
    .setRetryAttempts(3)                // 재시도 횟수
    .setRetryInterval(1500)             // 재시도 간격
```

### 4.2 클러스터 환경 설정
```kotlin
// 클러스터 환경
config.useClusterServers()
    .setNodeAddresses(
        "redis://cluster-1:6379",
        "redis://cluster-2:6379",
        "redis://cluster-3:6379"
    )
    .setMasterConnectionMinimumIdleSize(5)
    .setMasterConnectionPoolSize(20)
    .setSlaveConnectionMinimumIdleSize(5)
    .setSlaveConnectionPoolSize(20)
```

### 4.3 Sentinel 고가용성 설정
```kotlin
// Sentinel 환경 
config.useSentinelServers()
    .setMasterName("mymaster")
    .setSentinelAddresses(
        "redis://sentinel-1:26379",
        "redis://sentinel-2:26379", 
        "redis://sentinel-3:26379"
    )
```

## 5. 사용 시나리오별 설정

### 5.1 마이크로서비스 간 호출 제한
```kotlin
// 서비스별 독립적 제한
@RedissonRateLimited(
    rateType = RateType.PER_CLIENT,
    rate = 100, 
    rateInterval = 1,
    rateIntervalUnit = RateIntervalUnit.SECONDS
)
```

### 5.2 전체 시스템 부하 제어
```kotlin
// 모든 인스턴스 통합 제한
@RedissonRateLimited(
    rateType = RateType.OVERALL,
    rate = 1000,
    rateInterval = 1, 
    rateIntervalUnit = RateIntervalUnit.SECONDS
)
```

### 5.3 장기간 할당량 관리
```kotlin
// 일일/월간 할당량
@RedissonRateLimited(
    rate = 10000,
    rateInterval = 1,
    rateIntervalUnit = RateIntervalUnit.DAYS
)
```

### 5.4 고비용 연산 제어
```kotlin
// 허가 기반 제어
@RedissonRateLimited(
    rate = 100,
    permits = 10, // 한 번에 10개 허가 소모
    mode = RedissonRateLimitMode.BLOCKING,
    maxWaitTimeSeconds = 5
)
```

## 6. 성능 특성

### 6.1 Redisson 최적화 기술
- **연결 풀링**: 효율적인 Redis 연결 관리
- **파이프라이닝**: 다중 명령 배치 처리
- **Lua 스크립트**: 네트워크 라운드트립 최소화
- **로컬 캐싱**: 자주 사용하는 설정 캐시

### 6.2 분산 환경 최적화
- **샤딩**: 키 기반 자동 분산
- **복제**: Master-Slave 읽기 분산
- **장애 복구**: 자동 페일오버 지원
- **일관성**: 강일관성 보장

## 7. 모니터링 및 운영

### 7.1 Redisson 특화 모니터링
```kotlin
// 런타임 상태 조회
val status = rateLimiter.availablePermits()
val config = rateLimiter.configuration

// JMX 메트릭 활용
val connectionPoolInfo = redissonClient.connectionManager.connectionPool
```

### 7.2 분산 환경 모니터링
- **클러스터 상태**: 노드별 연결 상태
- **샤딩 분포**: 키 분산 균형도
- **복제 지연**: Master-Slave 동기화 상태
- **장애 복구**: 페일오버 발생 빈도

## 8. 고급 기능 활용

### 8.1 멀티 레벨 Rate Limiting
```kotlin
// 계층적 제한 구조
class HierarchicalRateLimiter {
    private val perSecondLimiter = redisson.getRateLimiter("second")
    private val perMinuteLimiter = redisson.getRateLimiter("minute")  
    private val perHourLimiter = redisson.getRateLimiter("hour")
    
    fun checkAllLevels(): Boolean {
        return perSecondLimiter.tryAcquire() &&
               perMinuteLimiter.tryAcquire() &&
               perHourLimiter.tryAcquire()
    }
}
```

### 8.2 동적 설정 변경
```kotlin
// 런타임 설정 변경
rateLimiter.trySetRate(RateType.PER_CLIENT, newRate, newInterval, unit)

// 설정 변경 알림
rateLimiter.addListener { oldConfig, newConfig ->
    logger.info("Rate limit changed: $oldConfig -> $newConfig")
}
```

## 9. 장단점 분석

### 9.1 장점
- ✅ **완벽한 분산 동기화**: 클러스터 환경에서 100% 일관성
- ✅ **자동화된 관리**: TTL, 클린업, 장애 복구 모두 자동
- ✅ **다양한 알고리즘**: Token Bucket, Leaky Bucket 지원
- ✅ **풍부한 기능**: 대기, 즉시, 강제 획득 모드
- ✅ **프로덕션 레벨**: 대규모 환경에서 검증된 안정성
- ✅ **Spring 통합**: 완벽한 Spring Boot 지원

### 9.2 단점
- ❌ **무거운 의존성**: Redisson 전체 스택 필요
- ❌ **복잡한 설정**: 클러스터/Sentinel 설정 복잡
- ❌ **러닝 커브**: Redisson 생태계 학습 필요
- ❌ **오버엔지니어링**: 단순한 용도에는 과도
- ❌ **벤더 락인**: Redisson 특화된 구현

## 10. 실제 사용 사례

### 10.1 대규모 전자상거래
- **주문 시스템**: 초당 주문 건수 제한
- **재고 관리**: 동시 재고 차감 제어
- **결제 시스템**: 사용자별 결제 시도 제한

### 10.2 금융 서비스
- **거래 제한**: 일일 거래 한도
- **API 호출**: 외부 금융망 호출 제한
- **보안 인증**: 로그인 시도 횟수 제한

### 10.3 게임 서비스
- **아이템 드롭**: 시간당 아이템 획득 제한
- **경험치 획득**: 레벨업 속도 조절
- **PvP 매칭**: 매칭 요청 빈도 제한

## 11. 면접 평가 포인트

### 11.1 핵심 질문
1. "PER_CLIENT와 OVERALL 모드의 차이는 무엇이고 언제 사용하나요?"
2. "BLOCKING 모드에서 데드락이 발생할 수 있는 상황은?"
3. "Redisson을 선택하는 기준은 무엇인가요?"

### 11.2 실무 관점 질문
1. "클러스터 환경에서 Rate Limiter의 일관성은 어떻게 보장되나요?"
2. "Redisson 의존성이 과도하다고 생각되는 상황은?"
3. "직접 구현 vs Redisson 활용의 판단 기준은?"

이 Redisson 구현은 **완벽한 분산 동기화**와 **자동화된 관리**를 제공하지만, **무거운 의존성**과 **복잡한 설정**이라는 트레이드오프를 가진 엔터프라이즈급 솔루션입니다.
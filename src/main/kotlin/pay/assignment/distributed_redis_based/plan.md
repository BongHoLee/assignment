# Redis 기반 분산 Rate Limiter 구현 계획 (v4)

## 알고리즘 선택 근거

### Redis 기반 Rate Limiting이란?
- **개념**: Redis의 원자적 연산(INCR, EXPIRE)을 활용한 분산 환경 Rate Limiting
- **특징**: 여러 서버 인스턴스 간 상태 공유, 데이터 영속성, 수평 확장 가능
- **구현**: Fixed Window 알고리즘을 Redis로 구현하여 분산 환경 대응

### Redis 키 전략
```
키 형식: "ratelimit:{userId}:{windowStart}"
예시: "ratelimit:user123:1701234000"

윈도우 계산:
- 5초 윈도우에서 7초 → 윈도우 시작: 5초 (1701234005)
- 5초 윈도우에서 12초 → 윈도우 시작: 10초 (1701234010)
```

### 선택 이유
1. **분산 환경 지원**: 여러 서버에서 동일한 Rate Limit 상태 공유
2. **영속성**: 서버 재시작 시에도 제한 상태 유지
3. **검증된 성능**: Redis의 높은 성능과 안정성
4. **원자성**: INCR 연산으로 경합 조건 없는 정확한 카운팅
5. **확장성**: Redis 클러스터를 통한 수평 확장

## 핵심 구현 사항

### 1. Redis 연산 활용
```kotlin
// 1. 원자적 카운터 증가
val currentCount = redisClient.incr(redisKey)

// 2. 첫 요청인 경우 TTL 설정 (자동 정리)
if (currentCount == 1L) {
    redisClient.expire(redisKey, rule.timeWindowSeconds + 1)
}

// 3. 제한 검사
val allowed = currentCount <= rule.maxRequest
```

### 2. 윈도우 시작점 계산
```kotlin
// Fixed Window 경계에 정렬
val windowMs = rule.timeWindowSeconds * 1000L
val windowStartMs = (nowMs / windowMs) * windowMs
val windowStartSeconds = windowStartMs / 1000
```

### 3. Redis 키 네임스페이스
```kotlin
val redisKey = "$keyPrefix:${userId.id}:$windowStartSeconds"
// 결과: "ratelimit:user123:1701234000"
```

## 분산 아키텍처 고려사항

### 1. 다중 서버 인스턴스
```
[App Server 1] ─┐
[App Server 2] ─┼─→ [Redis Cluster]
[App Server 3] ─┘
```

- 모든 서버가 동일한 Redis 인스턴스 공유
- 사용자별 상태가 모든 서버에서 일관성 유지
- 서버 추가/제거 시에도 Rate Limit 상태 유지

### 2. Redis 고가용성
```
[Redis Master] ←─→ [Redis Replica]
      │
[App Servers]
```

- Master-Replica 구성으로 장애 대응
- Sentinel을 통한 자동 페일오버
- 클러스터 모드로 샤딩 지원

### 3. 네트워크 분할 대응
- Redis 연결 실패 시 Fail-Open 정책
- 로컬 캐시를 활용한 백업 메커니즘 (v5에서 구현)
- Circuit Breaker 패턴으로 연쇄 장애 방지

## 성능 특성 분석

### 1. 시간 복잡도
- **INCR 연산**: O(1)
- **EXPIRE 연산**: O(1)
- **전체 처리**: O(1) + 네트워크 지연

### 2. 네트워크 지연
```
로컬 처리: ~1-3μs
Redis 호출: ~0.1-1ms (네트워크에 따라)
총 처리 시간: ~0.1-1ms
```

### 3. 처리량
```
단일 Redis 인스턴스: ~100K ops/sec
Redis 클러스터: ~1M+ ops/sec (노드 수에 비례)
실제 Rate Limiting: ~10K-50K req/sec (네트워크 고려)
```

## 메모리 사용량 분석

### Redis 메모리 사용
```
키 크기: ~40 bytes ("ratelimit:user123:1701234000")
값 크기: ~1-3 bytes (카운터 값)
TTL 오버헤드: ~8 bytes
총 사용량: ~50 bytes per window per user
```

### 확장성 계산
```
1만 사용자, 동시 윈도우 1개: ~500KB
10만 사용자, 동시 윈도우 2개: ~10MB
100만 사용자, 평균 윈도우 1.5개: ~75MB
```

### 메모리 최적화
- TTL을 통한 자동 정리
- 키 압축 (해시 함수 활용)
- 배치 만료 처리

## 장점과 단점 분석

### 장점
1. **완벽한 분산 지원**: 여러 서버 간 일관된 상태
2. **영속성**: 서버 재시작 후에도 제한 상태 유지  
3. **검증된 안정성**: Redis의 프로덕션 검증된 성능
4. **수평 확장**: Redis 클러스터를 통한 무한 확장
5. **운영 편의성**: Redis 모니터링/관리 도구 활용

### 단점
1. **외부 의존성**: Redis 장애 시 영향
2. **네트워크 지연**: 로컬 처리보다 느림
3. **경계 문제**: Fixed Window의 근본적 한계
4. **복잡성 증가**: Redis 설정, 모니터링, 백업 필요
5. **비용**: Redis 인프라 비용

## 구현 세부사항

### 1. InMemoryRedisClient (데모용)
```kotlin
class InMemoryRedisClient : RedisClient {
    private val storage = mutableMapOf<String, String>()
    private val expiry = mutableMapOf<String, Long>()
    
    // 실제 Redis 연산을 메모리로 시뮬레이션
}
```

### 2. 에러 처리 전략
```kotlin
try {
    val currentCount = redisClient.incr(redisKey)
    // 정상 처리
} catch (e: Exception) {
    // Fail-Open: Redis 실패 시 허용
    return Decision(allowed = true, ...)
}
```

### 3. TTL 관리
```kotlin
// 윈도우 크기 + 여유 시간으로 TTL 설정
val ttlSeconds = rule.timeWindowSeconds + 1
redisClient.expire(redisKey, ttlSeconds)
```

## 실제 프로덕션 고려사항

### 1. Redis 설정 최적화
```redis
# 메모리 정책
maxmemory-policy allkeys-lru

# 영속성 설정  
save 60 1000

# 클러스터 설정
cluster-enabled yes
cluster-node-timeout 15000
```

### 2. 연결 풀 최적화
```kotlin
// Lettuce 예시
val redisClient = RedisClient.create("redis://localhost:6379")
val connection = redisClient.connect()

// 연결 풀 설정
GenericObjectPoolConfig().apply {
    maxTotal = 100
    maxIdle = 20
    minIdle = 5
}
```

### 3. 모니터링 지표
- Redis 응답 시간
- 키 만료율  
- 메모리 사용량
- 네트워크 트래픽
- Rate Limit 적중률

## 테스트 시나리오

### 1. 정확성 테스트
- 다중 서버에서 동시 요청 처리
- Redis 재시작 후 상태 복구
- 네트워크 분할 시나리오

### 2. 성능 테스트
- 다양한 부하에서 응답 시간 측정
- Redis 클러스터 확장성 테스트
- 네트워크 지연 영향 분석

### 3. 장애 테스트
- Redis 마스터 장애 시 페일오버
- 네트워크 분할 시 Fail-Open 동작
- 메모리 부족 시 동작 확인

## 다른 알고리즘과의 비교

| 측면 | 로컬 Token Bucket | 로컬 Sliding Log | Redis Fixed Window |
|------|------------------|-------------------|-------------------|
| **분산 지원** | ✗ | ✗ | ✅ |
| **영속성** | ✗ | ✗ | ✅ |
| **성능** | 높음 (~1μs) | 중간 (~10μs) | 중간 (~1ms) |
| **정확도** | 근사 | 완벽 | 경계 문제 |
| **복잡도** | 낮음 | 중간 | 중간 |
| **확장성** | 제한적 | 제한적 | 높음 |

## 실제 사용 권장 상황

### 적합한 경우
- **마이크로서비스**: 여러 서비스 간 공통 Rate Limit
- **오토스케일링**: 서버 인스턴스 수가 동적으로 변하는 환경
- **글로벌 제한**: 전체 사용자에 대한 통합 제한
- **영속성 필요**: 서버 재시작 후에도 제한 상태 유지 필요

### 부적합한 경우
- **매우 낮은 지연시간**: 마이크로초 단위 응답 필요
- **네트워크 제약**: Redis 연결이 불안정한 환경
- **단일 서버**: 분산이 필요 없는 단순한 환경
- **복잡한 알고리즘**: Token Bucket 등이 필요한 경우

## 확장 계획

### v4.1: Sliding Window Log + Redis
```lua
-- Redis Lua 스크립트로 정확한 Sliding Window 구현
local key = KEYS[1]
local window = ARGV[1]
local limit = ARGV[2]
local now = ARGV[3]

-- 만료된 요청 제거 + 새 요청 추가를 원자적으로 수행
```

### v4.2: Redis Streams 활용
```redis
# Redis Streams를 이용한 정밀한 시간 기반 처리
XADD ratelimit:user123 * req 1
XRANGE ratelimit:user123 (now-window) +
```

### v4.3: Redis 클러스터 최적화
- Consistent Hashing으로 사용자별 노드 분산
- Hot Key 문제 해결
- 지역별 Redis 클러스터 구성
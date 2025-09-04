# Spring Cloud Gateway Redis Rate Limiter 구현 계획서

## 1. 개요

### 1.1 Spring Cloud Gateway RedisRateLimiter란?
Spring Cloud Gateway에서 제공하는 **검증된 분산 Rate Limiter** 구현체입니다. 대규모 마이크로서비스 환경에서 안정성이 검증되었으며, Token Bucket 알고리즘을 기반으로 합니다.

### 1.2 선택 이유
- **검증된 안정성**: Netflix, 아마존 등 대규모 환경에서 검증
- **완성도**: 프로덕션 레벨의 기능과 최적화
- **Spring 생태계 통합**: 완벽한 Spring Boot 통합
- **Reactive 지원**: WebFlux 기반 비동기 처리

## 2. 아키텍처 설계

### 2.1 전체 구조
```
┌─────────────────────────────────────────────────────────┐
│                Spring Cloud Gateway                    │
│  ┌─────────────────────────────────────────────────┐    │
│  │              RedisRateLimiter                   │    │
│  │  ┌─────────────────────────────────────────┐    │    │
│  │  │           Lua Script                    │    │    │
│  │  │  • Token Bucket Algorithm               │    │    │
│  │  │  • Atomic Operations                    │    │    │
│  │  └─────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
                    ┌─────────────────┐
                    │   Redis Cluster │
                    │  • tokens_key   │
                    │  • timestamp_key│
                    └─────────────────┘
```

### 2.2 핵심 개념
- **replenishRate**: 초당 토큰 보충 속도 (평균 처리율)
- **burstCapacity**: 버킷 최대 용량 (버스트 허용량)
- **requestedTokens**: 요청당 소모 토큰 수

## 3. 알고리즘 분석: Gateway Token Bucket

### 3.1 Spring Cloud Gateway 구현 특징
```kotlin
// Gateway의 핵심 수식
filled_tokens = min(capacity, tokens + (delta * rate))
allowed = filled_tokens >= requested
new_tokens = filled_tokens - (allowed ? requested : 0)
```

### 3.2 고유한 특징
1. **이중 키 구조**: tokens_key + timestamp_key 분리 저장
2. **정확한 시간 계산**: 마이크로초 수준의 정밀한 시간 계산
3. **버킷 크기 자동 조정**: max(capacity, requested)로 동적 조정
4. **TTL 자동 관리**: 설정 가능한 자동 만료

### 3.3 Lua Script 최적화
```lua
-- Spring Cloud Gateway의 검증된 Lua 스크립트
local tokens = tonumber(redis.call("get", tokens_key))
local last_refill = tonumber(redis.call("get", timestamp_key))
local delta = math.max(0, now - last_refill)
local filled_tokens = math.min(capacity, tokens + (delta * rate))
```

## 4. MVC 환경 적응 구현

### 4.1 Reactive → Blocking 변환
```kotlin
// Reactive Mono를 동기 방식으로 변환
val result = springCloudGatewayRateLimiter.isAllowed(userId, config)
    .block() // 실제 Gateway 환경에서는 비권장
```

### 4.2 사용자 식별 전략
Gateway 환경에서 일반적인 식별 방법들:
1. **X-User-Id**: 명시적 사용자 ID
2. **JWT Token**: Authorization 헤더 파싱
3. **API Key**: X-API-Key 헤더
4. **Gateway Headers**: X-Forwarded-For, CF-Connecting-IP 등

## 5. 설정 옵션 분석

### 5.1 ReplenishRate 설정
```kotlin
replenishRate = 10 // 초당 10개 토큰 보충
// 의미: 평균적으로 초당 10개 요청 허용
```

### 5.2 BurstCapacity 설정
```kotlin
burstCapacity = 20 // 최대 20개 토큰 보유
// 의미: 순간적으로 20개 요청까지 처리 가능
```

### 5.3 RequestedTokens 설정
```kotlin
requestedTokens = 1 // 요청당 1개 토큰 소모
// 고비용 API는 2개 이상으로 설정 가능
```

## 6. 성능 특성

### 6.1 검증된 성능
- **대규모 검증**: Netflix, AWS 등에서 초당 수십만 요청 처리
- **최적화된 Lua**: 수년간의 최적화가 적용된 스크립트
- **메모리 효율성**: 사용자당 2개 키만 사용

### 6.2 Redis 연산 최적화
```lua
-- 단일 Lua 스크립트로 모든 연산 원자화
-- GET tokens_key
-- GET timestamp_key  
-- SET tokens_key new_tokens
-- SET timestamp_key now
-- 모든 연산이 하나의 원자적 실행 단위
```

## 7. 설정 시나리오

### 7.1 일반 API 서비스
```kotlin
@GatewayRateLimited(replenishRate = 100, burstCapacity = 200)
// 평균 100 TPS, 최대 200 요청 버스트
```

### 7.2 보안 중심 API
```kotlin
@GatewayRateLimited(replenishRate = 10, burstCapacity = 15)
// 엄격한 제한: 평균 10 TPS, 제한된 버스트
```

### 7.3 배치 처리 API
```kotlin
@GatewayRateLimited(replenishRate = 5, burstCapacity = 100)
// 평균 낮음, 하지만 배치 처리를 위한 큰 버스트 허용
```

### 7.4 프리미엄 API
```kotlin
@GatewayRateLimited(replenishRate = 1000, burstCapacity = 2000, requestedTokens = 1)
// 높은 처리량 제공
```

## 8. 모니터링 및 운영

### 8.1 Gateway 환경 모니터링
- **처리량**: replenishRate 대비 실제 요청 처리량
- **버스트 사용**: burstCapacity 활용 패턴
- **토큰 고갈**: 빈번한 토큰 부족 상황
- **Redis 성능**: 연결 풀, 응답 시간

### 8.2 알림 및 대응
```yaml
# Gateway 환경 알림 기준
alerts:
  - token_exhaustion_rate > 10%
  - redis_connection_failure
  - high_response_latency > 100ms
```

## 9. 실제 Gateway vs 이 구현의 차이

### 9.1 실제 Gateway 환경
```kotlin
// WebFlux 기반 Reactive
return rateLimiter.isAllowed(routeId, id)
    .flatMap { response ->
        if (response.isAllowed) {
            chain.filter(exchange)
        } else {
            exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
            exchange.response.setComplete()
        }
    }
```

### 9.2 이 구현 (MVC 적응)
```kotlin
// Spring MVC 기반 동기 처리
val result = rateLimiter.isAllowed(userId, config).block()
if (!result.allowed) {
    throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, ...)
}
```

## 10. 확장성 고려사항

### 10.1 Redis 클러스터 지원
- **키 분산**: 사용자 ID 기반 해싱으로 부하 분산
- **일관성**: 동일 사용자는 동일 샤드에 배치
- **장애 대응**: Redis Sentinel/Cluster 자동 페일오버

### 10.2 멀티 테넌트 지원
```kotlin
val key = "rate_limit:${tenantId}:${userId}:${routeId}"
// 테넌트별 격리된 Rate Limiting
```

## 11. 장단점 분석

### 11.1 장점
- ✅ **검증된 안정성**: 대규모 프로덕션 환경 검증
- ✅ **완성도 높은 구현**: 엣지 케이스까지 모두 고려
- ✅ **Spring 생태계 통합**: 완벽한 호환성
- ✅ **성능 최적화**: 수년간의 최적화 적용
- ✅ **문서화**: 풍부한 문서와 커뮤니티 지원

### 11.2 단점
- ❌ **무거운 의존성**: Gateway 전체 스택 필요
- ❌ **WebFlux 환경 권장**: MVC에서는 제한적
- ❌ **커스터마이징 제약**: 내부 로직 변경 어려움
- ❌ **오버엔지니어링**: 단순한 용도에는 과도할 수 있음

## 12. 면접 평가 포인트

### 12.1 핵심 질문
1. "왜 직접 구현하지 않고 Gateway의 구현을 가져다 쓰나요?"
2. "Reactive 환경이 아닌 MVC에서 사용할 때의 제약사항은?"
3. "Gateway 환경과 일반 Spring Boot 환경의 차이점은?"

### 12.2 실무 관점 질문
1. "실제 Gateway를 도입하지 않고 이 방식만 사용해도 될까요?"
2. "Netflix 등에서 검증된 구현체를 쓰는 것의 장단점은?"
3. "커스터마이징이 필요하다면 어떻게 접근하시겠습니까?"

이 구현은 **검증된 안정성**과 **프로덕션 레벨 완성도**를 제공하지만, **무거운 의존성**과 **제한적 커스터마이징**이라는 트레이드오프를 가진 접근법입니다.
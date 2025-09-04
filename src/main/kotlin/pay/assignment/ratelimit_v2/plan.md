# Token Bucket 기반 Rate Limiter 구현 계획서

## 1. 개요

### 1.1 Token Bucket 알고리즘이란?
Token Bucket은 네트워크 트래픽 제어에서 널리 사용되는 알고리즘으로, **버스트 트래픽을 허용하면서도 평균적인 처리율을 제한**하는 특징을 가집니다.

### 1.2 동작 원리
1. **토큰 버킷**: 각 사용자는 독립적인 토큰 버킷을 가짐
2. **토큰 소모**: 요청이 올 때마다 버킷에서 토큰을 차감
3. **토큰 보충**: 설정된 속도로 시간에 따라 토큰을 자동 보충
4. **용량 제한**: 버킷 최대 용량을 초과하지 않도록 제한

## 2. Fixed Window vs Token Bucket 비교

### 2.1 Fixed Window (ratelimit_v1)
```
시간 ->  [----5초----][----5초----][----5초----]
요청 ->  xxx___________xxx___________xxx_______
특징: 정확한 시간 윈도우, 경계 효과 존재
```

### 2.2 Token Bucket (ratelimit_v2)  
```
시간 ->  [토큰충전][토큰충전][토큰충전][토큰충전]
요청 ->  xxxxx_____x___x______xxx_____________
특징: 버스트 허용, 평균 속도 제한, 부드러운 제어
```

## 3. 구현 세부사항

### 3.1 Redis 데이터 구조
```redis
HMSET token_bucket:{userId}:{rule_hash}
  tokens        : 현재 토큰 수
  lastRefillMs  : 마지막 토큰 보충 시간 (밀리초)
EXPIRE token_bucket:{userId}:{rule_hash} TTL
```

### 3.2 핵심 Lua Script 로직
```lua
-- 시간 경과 계산
timePassed = nowMs - lastRefillMs

-- 토큰 보충 계산  
tokensToAdd = floor(timePassed / refillPeriodMs * refillTokens)

-- 용량 초과 방지
currentTokens = min(capacity, currentTokens + tokensToAdd)

-- 요청 처리
if currentTokens >= requestedTokens then
    currentTokens = currentTokens - requestedTokens
    allowed = true
end
```

### 3.3 어노테이션 설계
```kotlin
@TokenBucketLimited(
    capacity = 5,              // 최대 5개 토큰 (버스트 용량)
    refillTokens = 1,          // 1개씩 보충
    refillPeriodSeconds = 1,   // 1초마다
    tokensPerRequest = 1       // 요청당 1토큰 소모
)
// 결과: 평균 1TPS, 최대 5요청 버스트 허용
```

## 4. Token Bucket의 장점

### 4.1 버스트 트래픽 허용
- **시나리오**: 사용자가 5분간 요청하지 않다가 갑자기 5개 요청
- **Fixed Window**: 첫 3개만 허용, 나머지 거부
- **Token Bucket**: 모든 5개 요청 허용 (토큰이 축적되었으므로)

### 4.2 부드러운 제어
- **Fixed Window**: 윈도우 경계에서 급격한 제한
- **Token Bucket**: 시간에 따른 점진적 회복

### 4.3 유연한 설정
- **capacity**: 버스트 크기 제어
- **refillRate**: 평균 처리율 제어
- **tokensPerRequest**: 요청별 비용 차등 적용

## 5. 사용 시나리오별 설정 예시

### 5.1 일반 API (평균 1TPS, 버스트 5요청)
```kotlin
@TokenBucketLimited(capacity = 5, refillTokens = 1, refillPeriodSeconds = 1)
```

### 5.2 리소스 집약적 API (평균 0.5TPS, 버스트 3요청)
```kotlin
@TokenBucketLimited(capacity = 3, refillTokens = 1, refillPeriodSeconds = 2)
```

### 5.3 비용 차등 API (일반 1토큰, 비용 높은 API 3토큰)
```kotlin
// 일반 API
@TokenBucketLimited(capacity = 10, refillTokens = 2, refillPeriodSeconds = 1, tokensPerRequest = 1)

// 비용 높은 API  
@TokenBucketLimited(capacity = 10, refillTokens = 2, refillPeriodSeconds = 1, tokensPerRequest = 3)
```

## 6. 성능 특성

### 6.1 Redis 연산
- **읽기**: HMGET (토큰 수, 마지막 보충 시간)
- **쓰기**: HMSET (상태 업데이트) + EXPIRE (TTL)
- **원자성**: 모든 연산이 Lua Script로 원자적 실행

### 6.2 메모리 효율성
- **사용자당**: 2개 값 (tokens, lastRefillMs)
- **TTL**: 자동 정리 (capacity / refillRate * 2)
- **키 네이밍**: `token_bucket:{userId}:{rule_hash}`

## 7. 모니터링 지표

### 7.1 Token Bucket 특화 지표
- **토큰 사용률**: 시간당 소모된 토큰 수
- **버킷 고갈 빈도**: 토큰 부족으로 거부된 요청 비율
- **평균 토큰 보유량**: 버킷의 평균 토큰 수
- **버스트 패턴**: 연속적인 토큰 소모 패턴

### 7.2 알림 기준
- 토큰 부족 거부율 > 10%
- 평균 토큰 보유량 < capacity * 0.3
- Redis 연결 실패

## 8. Token Bucket vs 다른 알고리즘

| 특성 | Fixed Window | Token Bucket | Sliding Window |
|------|-------------|-------------|----------------|
| 버스트 허용 | ❌ | ✅ | △ |
| 구현 복잡도 | 낮음 | 중간 | 높음 |
| 메모리 사용량 | 낮음 | 낮음 | 높음 |
| 정확성 | 높음 | 중간 | 높음 |
| 부드러운 제어 | ❌ | ✅ | ✅ |

## 9. 실제 사용 사례

### 9.1 API Gateway
- AWS API Gateway의 Token Bucket 구현
- 버스트 한도와 정상 상태 한도 분리

### 9.2 CDN/네트워크
- 네트워크 트래픽 쉐이핑
- QoS(Quality of Service) 구현

### 9.3 마이크로서비스
- 서비스간 호출 제한
- 백엔드 보호

## 10. 장단점 분석

### 10.1 장점
- ✅ **버스트 트래픽 허용**: 일시적 트래픽 급증 대응
- ✅ **사용자 친화적**: 대기 후 요청 가능
- ✅ **유연한 설정**: 다양한 시나리오 대응
- ✅ **부드러운 제어**: 급격한 제한 없음

### 10.2 단점
- ❌ **복잡성**: Fixed Window보다 구현 복잡
- ❌ **부정확성**: 순간적으로 평균보다 많은 요청 가능
- ❌ **설정 어려움**: 적절한 capacity/refillRate 선택 필요

## 11. 면접 평가 포인트

### 11.1 핵심 질문
1. "Token Bucket에서 토큰이 부족할 때 언제 다시 시도할 수 있는지 어떻게 계산하나요?"
2. "버킷 용량(capacity)과 보충 속도(refillRate)를 어떻게 결정하시겠습니까?"
3. "Token Bucket이 Fixed Window보다 적합한 상황은 언제인가요?"

### 11.2 심화 질문
1. "토큰 보충 과정에서 부동소수점 오차는 어떻게 처리하나요?"
2. "여러 API가 같은 토큰 버킷을 공유해야 한다면 어떻게 구현하시겠습니까?"
3. "Token Bucket의 공정성(fairness) 문제는 무엇인가요?"

이 Token Bucket 구현은 **버스트 트래픽을 우아하게 처리**하면서도 **장기적인 평균 속도를 제어**할 수 있는 실용적인 Rate Limiting 솔루션입니다.
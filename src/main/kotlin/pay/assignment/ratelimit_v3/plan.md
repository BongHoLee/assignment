# Sliding Window 기반 Rate Limiter 구현 계획서

## 1. 개요

### 1.1 Sliding Window 알고리즘이란?
Sliding Window는 **고정 시간 윈도우의 경계 효과 문제를 해결**하는 정교한 Rate Limiting 알고리즘입니다. 각 요청의 타임스탬프를 저장하여 정확한 시간 윈도우 내에서만 요청을 카운트합니다.

### 1.2 동작 원리
1. **요청 기록**: 각 요청을 타임스탬프와 함께 Sorted Set에 저장
2. **윈도우 슬라이딩**: 현재 시간 기준으로 윈도우 범위 계산
3. **오래된 요청 제거**: 윈도우 밖의 요청들을 자동 제거
4. **정확한 카운팅**: 윈도우 내 요청 수만 정확히 카운트

## 2. Fixed Window vs Sliding Window 비교

### 2.1 Fixed Window의 경계 효과 문제
```
시간축: |--5초--|--5초--|--5초--|
요청:   XXX_____ XXXXX___ XXX_____
문제:   4초에 3개 + 6초에 3개 = 2초 간격에 6개 요청!
```

### 2.2 Sliding Window의 해결책
```
시간축: ←----5초 윈도우---->
요청:   X_X___X_X___X___X
확인:   각 시점에서 최근 5초간 정확히 3개만 허용
```

## 3. 구현 세부사항

### 3.1 Redis 데이터 구조
```redis
# Redis Sorted Set 사용
ZADD sliding_window:{userId}:{rule} {timestamp} {request_id}
ZREMRANGEBYSCORE sliding_window:{userId}:{rule} -inf {windowStart}
ZCARD sliding_window:{userId}:{rule}
```

### 3.2 핵심 Lua Script 로직
```lua
-- 윈도우 범위 계산
windowStartMs = nowMs - windowMs

-- 윈도우 밖 요청 제거
redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStartMs)

-- 현재 윈도우 요청 수 확인
currentCount = redis.call('ZCARD', key)

-- 제한 확인 후 새 요청 추가
if currentCount < maxRequests then
    redis.call('ZADD', key, nowMs, requestId)
end
```

### 3.3 고유 요청 ID 생성
```kotlin
val requestId = "${nowMs}_${(Math.random() * 1000).toInt()}"
```

## 4. Sliding Window의 장점

### 4.1 정확한 시간 제어
- **Fixed Window**: 윈도우 경계에서 순간적으로 2배 요청 가능
- **Sliding Window**: 언제나 정확히 지정된 시간 범위 내에서만 카운트

### 4.2 부드러운 제어
- 시간이 지남에 따라 자연스럽게 요청 허용
- 갑작스러운 제한 변화 없음

### 4.3 정밀한 분석
- 각 요청의 정확한 시간 정보 보존
- 상세한 사용 패턴 분석 가능

## 5. 성능 및 메모리 고려사항

### 5.1 메모리 사용량
- **장점**: 정확한 제어
- **단점**: 각 요청의 타임스탬프 저장 필요
- **계산**: 사용자당 최대 maxRequests개의 타임스탬프 저장

### 5.2 Redis 연산 복잡도
- **ZREMRANGEBYSCORE**: O(log(N) + M) - N은 전체 요청 수, M은 제거할 요청 수
- **ZCARD**: O(1)
- **ZADD**: O(log(N))
- **전체**: O(log(N) + M) - 대부분의 경우 효율적

### 5.3 TTL 최적화
```kotlin
// TTL = 윈도우 크기의 2배 (안전 여유분)
redis.call('EXPIRE', key, math.ceil(windowMs / 1000 * 2))
```

## 6. 사용 시나리오별 설정

### 6.1 정밀한 API 제어
```kotlin
@SlidingWindowLimited(maxRequests = 100, windowSeconds = 60)
// 정확히 1분 동안 100개 요청만 허용
```

### 6.2 버스트 방지
```kotlin
@SlidingWindowLimited(maxRequests = 5, windowSeconds = 10)
// 10초 윈도우로 버스트 트래픽도 정밀 제어
```

### 6.3 분석용 상세 로깅
```kotlin
// 각 요청의 타임스탬프가 보존되어 상세 분석 가능
```

## 7. 모니터링 지표

### 7.1 Sliding Window 특화 지표
- **윈도우 활용률**: 현재 윈도우에서 사용된 요청 비율
- **시간별 요청 분포**: 윈도우 내에서 요청이 몰린 시간대
- **오래된 요청 정리 빈도**: ZREMRANGEBYSCORE 연산 횟수
- **메모리 사용 패턴**: Sorted Set 크기 변화

### 7.2 알림 기준
- 윈도우 활용률 > 90%
- 평균 윈도우 크기 > maxRequests * 0.8
- Redis 메모리 사용량 급증

## 8. Fixed Window vs Token Bucket vs Sliding Window

| 특성 | Fixed Window | Token Bucket | **Sliding Window** |
|------|-------------|-------------|-------------------|
| 정확성 | 중간 (경계 효과) | 중간 (버스트 허용) | **높음 (정확한 윈도우)** |
| 구현 복잡도 | 낮음 | 중간 | **높음** |
| 메모리 사용량 | 낮음 | 낮음 | **높음 (요청별 저장)** |
| 부드러운 제어 | ❌ | ✅ | **✅** |
| 버스트 허용 | ❌ | ✅ | **❌ (정확한 제한)** |
| 분석 가능성 | 낮음 | 낮음 | **높음 (상세 기록)** |

## 9. 실제 사용 사례

### 9.1 금융 API
- 정확한 거래 횟수 제한 필요
- 규제 준수를 위한 정밀한 제어

### 9.2 보안 API
- 로그인 시도 제한
- 브루트포스 공격 방지

### 9.3 프리미엄 API
- 정확한 할당량 관리
- 과금을 위한 정밀한 사용량 추적

## 10. 장단점 분석

### 10.1 장점
- ✅ **완벽한 정확성**: 언제나 정확한 시간 윈도우 적용
- ✅ **경계 효과 없음**: Fixed Window의 문제점 완전 해결  
- ✅ **부드러운 제어**: 시간에 따른 자연스러운 허용/거부
- ✅ **상세한 분석**: 각 요청의 타임스탬프 보존
- ✅ **규제 준수**: 정확한 제한으로 컴플라이언스 만족

### 10.2 단점
- ❌ **높은 메모리 사용량**: 각 요청 타임스탬프 저장
- ❌ **복잡한 구현**: Sorted Set 관리의 복잡성
- ❌ **상대적 고비용**: 더 많은 Redis 연산 필요
- ❌ **버스트 미허용**: 엄격한 제한으로 유연성 부족

## 11. 면접 평가 포인트

### 11.1 핵심 질문
1. "Sliding Window가 Fixed Window의 경계 효과를 어떻게 해결하나요?"
2. "왜 Sorted Set을 사용했나요? Hash나 다른 자료구조는 안 되나요?"
3. "메모리 사용량이 높은데 어떻게 최적화할 수 있을까요?"

### 11.2 심화 질문  
1. "동일한 타임스탬프를 가진 요청들은 어떻게 구분하나요?"
2. "Redis Sorted Set의 시간 복잡도를 고려할 때 성능 한계는?"
3. "Sliding Window와 Token Bucket을 결합할 수 있을까요?"

이 Sliding Window 구현은 **완벽한 정확성**을 제공하지만 **높은 메모리 비용**을 수반하는 트레이드오프를 가진 정교한 Rate Limiting 솔루션입니다.
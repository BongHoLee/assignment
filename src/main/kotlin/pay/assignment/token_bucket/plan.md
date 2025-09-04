# Token Bucket 알고리즘 구현 계획 (v1)

## 알고리즘 선택 근거

### Token Bucket이란?
- **개념**: 토큰이 일정한 속도로 버킷에 채워지고, 요청시마다 토큰을 소비하는 방식
- **특징**: 버스트 트래픽을 허용하면서도 평균적인 처리율을 보장
- **용량**: 버킷 크기 = 최대 허용 요청 수 (3개)
- **충전율**: 토큰 충전 속도 = 3개/5초 = 0.6개/초

### 선택 이유
1. **버스트 허용**: 짧은 시간 내 연속 요청을 자연스럽게 처리
2. **직관성**: 개념이 명확하고 이해하기 쉬움
3. **유연성**: 용량과 충전율을 독립적으로 조절 가능
4. **실용성**: 많은 실제 시스템에서 사용되는 검증된 방법

## 핵심 구현 사항

### 1. 데이터 구조
```kotlin
data class TokenBucketState(
    var tokens: Double,           // 현재 토큰 수 (소수점 허용)
    var lastRefillMillis: Long    // 마지막 충전 시간
)
```

### 2. 토큰 충전 로직
```kotlin
private fun refillTokens(nowMs: Long, capacity: Int, refillRatePerSecond: Double) {
    val elapsedMs = nowMs - lastRefillMillis
    if (elapsedMs > 0) {
        val tokensToAdd = (elapsedMs / 1000.0) * refillRatePerSecond
        tokens = (tokens + tokensToAdd).coerceAtMost(capacity.toDouble())
        lastRefillMillis = nowMs
    }
}
```

### 3. 요청 처리 로직
1. **토큰 충전**: 경과 시간에 따라 토큰 추가
2. **토큰 확인**: 1개 이상의 토큰이 있는지 확인
3. **토큰 소비**: 허용 시 토큰 1개 차감
4. **응답 생성**: 결과와 메타데이터 반환

## 동시성 처리 전략

### 선택한 방법: ConcurrentHashMap + synchronized
```kotlin
private val states: ConcurrentHashMap<StateKey, TokenBucketState> = ConcurrentHashMap()

// 사용자별 상태에 대해서만 동기화
return synchronized(state) {
    state.decide(nowMs, rule.maxRequest, refillRate)
}
```

### 동시성 고려사항
1. **사용자별 격리**: 각 사용자의 상태를 독립적으로 관리
2. **최소 락 범위**: 개별 상태 객체에만 synchronization 적용
3. **성능 최적화**: 전체 시스템이 아닌 사용자별 락으로 병목 최소화

## 메모리 사용량 분석

### 사용자당 메모리
- TokenBucketState: ~24 bytes (Double + Long)
- StateKey: ~32 bytes (UserId + windowSeconds)
- ConcurrentHashMap 오버헤드: ~16 bytes
- **총합: ~72 bytes per user**

### 확장성 계산
- 1만 사용자: ~720KB
- 10만 사용자: ~7.2MB
- 100만 사용자: ~72MB

## 성능 특성

### 장점
1. **O(1) 시간 복잡도**: 상수 시간 내 처리
2. **버스트 허용**: 초기에 최대 3개 요청을 연속으로 처리 가능
3. **부드러운 제한**: 급격한 차단보다는 점진적 제한

### 단점
1. **메모리 사용량**: 사용자별 상태 저장 필요
2. **정확성**: 부동소수점 연산으로 인한 미세한 오차 가능
3. **TTL 필요**: 비활성 사용자 상태 정리 로직 필요

## 구현 세부사항

### User ID 추출 전략
1. **헤더 우선**: X-User-ID 헤더
2. **세션 ID**: HTTP 세션 식별자
3. **IP 주소**: 원격 주소
4. **기본값**: "anonymous"

### 에러 응답 형식
```json
{
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Try again later.",
  "retryAfterSeconds": 1
}
```

### HTTP 헤더
- `X-RateLimit-Remaining`: 남은 토큰 수
- `Retry-After`: 다음 시도까지 대기 시간(초)

## 테스트 시나리오

### 기본 시나리오
1. 초기 3개 요청 연속 허용
2. 4번째 요청 즉시 차단
3. 1.67초 후 1개 토큰 충전 확인

### 버스트 시나리오
1. 5초 대기 후 3개 토큰 완전 충전 확인
2. 연속 3개 요청 허용 검증
3. 이후 0.6개/초 속도로 처리 확인

### 동시성 시나리오
1. 동일 사용자 동시 요청 처리
2. 다른 사용자 요청 독립성 확인
3. 높은 부하 상황에서 성능 측정
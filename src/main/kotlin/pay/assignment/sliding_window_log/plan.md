# Sliding Window Log 알고리즘 구현 계획 (v2)

## 알고리즘 선택 근거

### Sliding Window Log란?
- **개념**: 각 요청의 정확한 타임스탬프를 기록하고, 슬라이딩 윈도우 내의 요청 수를 계산
- **특징**: 가장 정확한 Rate Limiting 제공, 윈도우가 시간에 따라 연속적으로 이동
- **구현**: 요청 시각을 큐(Queue)에 저장, 윈도우 범위 벗어난 요청 실시간 제거

### 동작 예시 (5초 윈도우, 3개 제한)
```
시간축: --|--1s--2s--3s--4s--5s--6s--7s--8s-->
요청:      ✓   ✓   ✓        ✓
윈도우:                 [----5초----]

7초 시점 검사: [2s, 3s, 6s] → 3개 → 차단
8초 시점 검사: [3s, 6s] → 2개 → 허용
```

### 선택 이유
1. **최고 정확도**: 요청 시각을 정밀하게 기록하여 정확한 제한
2. **실시간 처리**: 윈도우가 연속적으로 이동하며 즉각 반영
3. **공정성**: 모든 요청이 동일한 기준으로 평가
4. **투명성**: 정확히 언제 다음 요청이 가능한지 계산 가능

## 핵심 구현 사항

### 1. 데이터 구조
```kotlin
data class SlidingWindowLogState(
    private val requestLog: ConcurrentLinkedQueue<Long>, // 요청 시각 큐
    var lastCleanupMillis: Long                          // TTL 관리용
)
```

### 2. 핵심 로직
```kotlin
fun decide(nowMs: Long, windowMs: Long, limit: Int): Decision {
    // 1. 오래된 요청 제거
    cleanupExpiredRequests(nowMs, windowMs)
    
    // 2. 현재 요청 수 확인
    val currentCount = requestLog.size
    
    // 3. 제한 검사 및 기록
    val allowed = currentCount < limit
    if (allowed) requestLog.offer(nowMs)
    
    return createDecision(allowed, limit, currentCount, nowMs, windowMs)
}
```

### 3. 오래된 요청 정리
```kotlin
private fun cleanupExpiredRequests(nowMs: Long, windowMs: Long) {
    val windowStartMs = nowMs - windowMs
    while (requestLog.peek()?.let { it < windowStartMs } == true) {
        requestLog.poll()
    }
}
```

## 동시성 처리 전략

### 선택한 방법: ConcurrentLinkedQueue + synchronized
```kotlin
private val requestLog: ConcurrentLinkedQueue<Long> = ConcurrentLinkedQueue()

// 사용자별 상태에 대해서만 동기화
return synchronized(state) {
    state.decide(nowMs, windowMs, rule.maxRequest)
}
```

### 동시성 고려사항
1. **ConcurrentLinkedQueue**: 기본적으로 thread-safe한 큐 사용
2. **복합 연산 보호**: peek() + poll() 등의 복합 연산을 synchronized로 보호
3. **사용자별 격리**: 각 사용자의 로그를 독립적으로 관리
4. **최소 락 범위**: 전체 시스템이 아닌 사용자별 상태만 동기화

## 메모리 사용량 분석

### 기본 메모리 구조
- Long 타입 타임스탬프: 8 bytes per request
- ConcurrentLinkedQueue 노드 오버헤드: ~16 bytes per request
- StateKey + 기타 오버헤드: ~32 bytes per user

### 사용자당 메모리 (최악의 경우)
```
최대 요청 수 = 3개 (제한값)
요청당 메모리 = 8 + 16 = 24 bytes
사용자당 최대 = 3 * 24 + 32 = ~104 bytes
```

### 확장성 계산
- 1만 사용자: ~1MB
- 10만 사용자: ~10MB  
- 100만 사용자: ~100MB

### Token Bucket과 비교
- Token Bucket: ~72 bytes per user (고정)
- Sliding Window Log: ~104 bytes per user (최대)
- 메모리 증가: 약 44% 증가

## 성능 특성

### 시간 복잡도
- **요청 처리**: O(k) (k = 만료된 요청 수, 평균적으로 상수)
- **최악의 경우**: O(n) (n = 윈도우 내 전체 요청 수)
- **일반적 경우**: O(1) ~ O(3) (제한값이 작으므로)

### 장점
1. **완벽한 정확도**: 요청 시각을 정밀 추적
2. **실시간 처리**: 윈도우 경계 문제 없음
3. **정확한 재시도**: 다음 허용 시각 정밀 계산
4. **공정한 처리**: 모든 요청 동일 기준 적용

### 단점
1. **메모리 사용량**: 요청 수에 비례하여 증가
2. **처리 시간**: 만료된 요청 정리 오버헤드
3. **복잡성**: 구현과 이해가 상대적으로 복잡

## 구현 세부사항

### 재시도 시간 계산
```kotlin
private fun calculateRetryAfter(allowed: Boolean, nowMs: Long, windowMs: Long): Long {
    if (allowed) return 0L
    
    val oldestRequestMs = requestLog.peek()
    return if (oldestRequestMs != null) {
        // 가장 오래된 요청이 윈도우를 벗어날 때까지의 시간
        ((oldestRequestMs + windowMs) - nowMs).coerceAtLeast(1L)
    } else {
        1000L // 안전장치
    }
}
```

### TTL 및 메모리 관리
1. **자동 정리**: 각 요청 시 만료된 요청 자동 제거
2. **주기적 정리**: `cleanupExpired()` 메서드로 비활성 사용자 제거
3. **메모리 모니터링**: `getActiveUserCount()`, `getUserRequestCount()` 제공

## 테스트 시나리오

### 정확성 테스트
1. **경계 조건**: 정확히 5초 경계에서의 요청 처리
2. **연속 요청**: 짧은 시간 내 연속 요청의 정확한 제한
3. **시간 흐름**: 시간 경과에 따른 요청 가능 상태 변화

### 성능 테스트
1. **메모리 사용량**: 다양한 사용자 수에서의 메모리 측정
2. **처리 시간**: 요청 처리 시간 측정 (만료 요청 정리 포함)
3. **동시성**: 동시 요청 처리 성능 및 정확성

### 극한 조건 테스트
1. **대량 사용자**: 10만+ 사용자 동시 요청
2. **높은 빈도**: 제한 한계에서의 지속적 요청
3. **장시간 운영**: 메모리 누수 및 성능 저하 확인

## Token Bucket과의 차이점

| 측면 | Token Bucket | Sliding Window Log |
|------|-------------|-------------------|
| **정확도** | 근사치 (토큰 기반) | 완벽 정확 (시각 기반) |
| **메모리** | 고정 (~72B/user) | 가변 (~104B/user 최대) |
| **성능** | O(1) | O(k), 최악 O(n) |
| **복잡도** | 단순 | 중간 |
| **버스트** | 자연스럽게 허용 | 정확한 제한 |
| **재시도** | 근사치 계산 | 정밀 계산 |

## 실제 사용 권장 상황

### 적합한 경우
- **정확한 제한**이 중요한 경우 (결제, 중요 API)
- **공정성**이 요구되는 환경
- **낮은 요청 빈도** (제한값이 작은 경우)
- **정밀한 모니터링**이 필요한 경우

### 부적합한 경우  
- **높은 처리량**이 중요한 경우
- **메모리가 제한**적인 환경
- **버스트 허용**이 필요한 경우
- **단순함**이 우선인 경우
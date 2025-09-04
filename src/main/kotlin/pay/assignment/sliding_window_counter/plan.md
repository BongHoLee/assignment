# Sliding Window Counter 알고리즘 구현 계획 (v3)

## 알고리즘 선택 근거

### Sliding Window Counter란?
- **개념**: 시간 창을 여러 개의 작은 서브윈도우(버킷)로 나누어 각각의 요청 수를 카운트
- **특징**: 현재 시점에서 윈도우에 포함되는 버킷들의 가중평균으로 요청 수 근사 계산
- **목표**: Sliding Window Log의 정확도와 Fixed Window의 효율성의 절충안

### 동작 예시 (5초 윈도우, 10개 버킷, 1초당 0.5개 버킷)
```
버킷 구조: [0-0.5s][0.5-1s][1-1.5s]...[4.5-5s]
시간축: --|--1s--2s--3s--4s--5s--6s--7s-->
요청:      ✓   ✓   ✓        ✓

2.3초 시점 검사 (윈도우: -2.7초 ~ 2.3초):
- 버킷별 카운트 및 가중치 적용
- 부분적으로 포함되는 버킷은 비율에 따라 계산
- 총 근사 요청 수로 제한 판단
```

### 선택 이유
1. **균형잡힌 정확도**: Sliding Window Log에 근접한 정확도
2. **메모리 효율**: 고정된 버킷 수로 메모리 사용량 예측 가능
3. **일정한 성능**: 버킷 수가 고정되어 O(1) 시간 복잡도
4. **실용성**: 많은 실제 시스템에서 채택하는 검증된 접근법

## 핵심 구현 사항

### 1. 데이터 구조
```kotlin
data class SlidingWindowCounterState(
    private val buckets: IntArray,              // 고정 크기 버킷 배열
    private val bucketDurationMs: Long,         // 버킷당 시간 범위
    private var windowStartMs: Long,            // 현재 윈도우 시작점
    var lastUpdateMs: Long                      // TTL 관리용
)
```

### 2. 핵심 설계 결정
- **버킷 개수**: 10개 (정확도와 성능의 균형점)
- **버킷 크기**: windowMs / 10 = 500ms (5초 ÷ 10)
- **슬라이딩 방식**: 배열 시프트를 통한 윈도우 이동
- **가중평균 계산**: 부분적으로 포함된 버킷의 비율 적용

### 3. 핵심 알고리즘
```kotlin
fun decide(nowMs: Long, windowMs: Long, limit: Int): Decision {
    // 1. 윈도우 슬라이딩 (배열 시프트)
    slideWindow(nowMs, windowMs)
    
    // 2. 가중평균으로 현재 요청 수 계산
    val currentCount = calculateWeightedCount(nowMs, windowMs)
    
    // 3. 제한 검사 및 카운트 증가
    val allowed = currentCount < limit
    if (allowed) incrementCurrentBucket(nowMs, windowMs)
    
    return createDecision(allowed, limit, currentCount, nowMs, windowMs)
}
```

### 4. 윈도우 슬라이딩 로직
```kotlin
private fun slideWindow(nowMs: Long, windowMs: Long) {
    val newWindowStartMs = nowMs - windowMs
    val timeDeltaMs = newWindowStartMs - windowStartMs
    val bucketsToSlide = (timeDeltaMs / bucketDurationMs).toInt()
    
    if (bucketsToSlide >= BUCKET_COUNT) {
        buckets.fill(0)  // 전체 초기화
    } else if (bucketsToSlide > 0) {
        // 배열 왼쪽 시프트 + 새 버킷 초기화
        for (i in 0 until BUCKET_COUNT - bucketsToSlide) {
            buckets[i] = buckets[i + bucketsToSlide]
        }
        for (i in BUCKET_COUNT - bucketsToSlide until BUCKET_COUNT) {
            buckets[i] = 0
        }
    }
}
```

## 동시성 처리 전략

### 선택한 방법: ConcurrentHashMap + synchronized
```kotlin
private val states: ConcurrentHashMap<StateKey, SlidingWindowCounterState> = ConcurrentHashMap()

return synchronized(state) {
    state.decide(nowMs, windowMs, rule.maxRequest)
}
```

### 동시성 고려사항
1. **배열 연산 보호**: IntArray 조작의 원자성 보장
2. **복합 계산 보호**: 가중평균 계산 중 상태 변경 방지
3. **사용자별 격리**: 각 사용자 상태의 독립적 처리
4. **최소 락 범위**: 전체가 아닌 개별 상태에만 동기화

## 메모리 사용량 분석

### 버킷 구조별 메모리
- IntArray (10개): 10 * 4 = 40 bytes
- Long 타입 필드들: 3 * 8 = 24 bytes
- StateKey + 오버헤드: ~32 bytes
- **총 사용자당**: ~96 bytes (고정)

### 다른 방식과 비교
```
Token Bucket: ~72 bytes (고정)
Sliding Window Log: ~104 bytes (최대, 가변)
Sliding Window Counter: ~96 bytes (고정)
Fixed Window: ~48 bytes (고정)
```

### 확장성 분석
- 1만 사용자: ~960KB
- 10만 사용자: ~9.6MB
- 100만 사용자: ~96MB

## 성능 특성 분석

### 시간 복잡도
- **윈도우 슬라이딩**: O(k) (k = 슬라이드할 버킷 수, 평균 1-2개)
- **가중평균 계산**: O(n) (n = 버킷 수 = 10, 상수)
- **전체 처리**: O(1) (버킷 수가 고정이므로 상수 시간)

### 공간 복잡도
- **사용자당**: O(1) (고정된 버킷 배열)
- **전체**: O(m) (m = 사용자 수)

### 장점
1. **예측 가능한 성능**: 항상 O(1) 시간 복잡도
2. **메모리 효율**: 고정 크기로 메모리 사용량 예측 가능
3. **균형잡힌 정확도**: 대부분의 경우 충분한 정확도
4. **구현 단순성**: Log 방식보다 상대적으로 단순

### 단점
1. **근사치 계산**: 완벽한 정확도는 아님
2. **설정 복잡성**: 버킷 수와 크기의 최적화 필요
3. **메모리 고정비용**: 사용하지 않는 버킷도 메모리 점유

## 정확도 분석

### 오차 발생 요인
1. **버킷 경계**: 버킷 내부의 요청 분포를 균등하다고 가정
2. **시간 근사**: 연속적인 시간을 불연속적인 버킷으로 근사
3. **가중평균**: 부분 포함 버킷의 선형 보간

### 정확도 개선 방법
1. **버킷 수 증가**: 더 세밀한 시간 분할 (메모리 trade-off)
2. **적응적 버킷**: 요청 패턴에 따른 동적 조정 (복잡성 증가)
3. **하이브리드**: 최근 요청은 정확히, 오래된 것은 근사 (v5에서 다룰 예정)

## 구현 세부사항

### 가중평균 계산
```kotlin
private fun calculateWeightedCount(nowMs: Long, windowMs: Long): Double {
    val windowEndMs = nowMs
    var totalCount = 0.0
    
    for (i in buckets.indices) {
        val bucketStartMs = windowStartMs + i * bucketDurationMs
        val bucketEndMs = bucketStartMs + bucketDurationMs
        
        val overlapStart = maxOf(bucketStartMs, windowEndMs - windowMs)
        val overlapEnd = minOf(bucketEndMs, windowEndMs)
        
        if (overlapStart < overlapEnd) {
            val overlapRatio = (overlapEnd - overlapStart).toDouble() / bucketDurationMs
            totalCount += buckets[i] * overlapRatio
        }
    }
    
    return totalCount
}
```

### 재시도 시간 계산
- **간단한 방식**: 다음 버킷까지의 시간 (bucketDurationMs)
- **정확한 방식**: 가장 오래된 버킷의 만료 시점 계산 가능

### 모니터링 기능
- `getActiveUserCount()`: 전체 사용자 수
- `getUserBuckets()`: 특정 사용자의 버킷 상태
- `getUserTotalCount()`: 특정 사용자의 총 요청 수

## 테스트 시나리오

### 정확도 테스트
1. **경계 테스트**: 버킷 경계에서의 요청 처리 정확성
2. **근사도 측정**: Sliding Window Log와 비교한 오차율
3. **시간 이동**: 윈도우 슬라이딩에 따른 카운트 변화

### 성능 테스트
1. **처리 시간**: 다양한 부하에서의 응답 시간 측정
2. **메모리 사용량**: 사용자 수에 따른 메모리 증가 패턴
3. **동시성**: 동시 요청 처리 성능

### 극한 조건 테스트
1. **시간 점프**: 시스템 시간 변경에 대한 대응
2. **대용량**: 10만+ 사용자 동시 요청
3. **버스트**: 집중적인 요청 패턴에서의 정확도

## 설정 최적화 가이드

### 버킷 수 선택 기준
- **적은 버킷 (5개)**: 낮은 정확도, 높은 성능, 적은 메모리
- **중간 버킷 (10개)**: 균형잡힌 특성 (권장)
- **많은 버킷 (20개)**: 높은 정확도, 낮은 성능, 많은 메모리

### 윈도우 크기별 권장사항
- **짧은 윈도우 (1-5초)**: 10개 버킷 권장
- **중간 윈도우 (5-60초)**: 15-20개 버킷
- **긴 윈도우 (1분+)**: 30개 이상 고려

## 실제 사용 권장 상황

### 적합한 경우
- **중간 정확도** 요구사항 (완벽하지 않아도 됨)
- **일정한 성능**이 중요한 환경
- **메모리 효율**이 필요한 시스템
- **구현 복잡도**를 적절히 유지하고 싶은 경우

### 부적합한 경우
- **완벽한 정확도**가 필수인 경우
- **매우 낮은 제한값** (1-2개)으로 근사 오차가 크게 작용
- **매우 높은 처리량**에서 버킷 연산 오버헤드 문제
- **단순함**이 최우선인 경우
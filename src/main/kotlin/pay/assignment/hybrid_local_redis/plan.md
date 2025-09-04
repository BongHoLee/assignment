# 하이브리드 Rate Limiter 구현 계획 (v5)

## 알고리즘 선택 근거

### 하이브리드 Rate Limiting이란?
- **개념**: 로컬 캐시와 분산 백엔드를 결합하여 성능과 일관성을 모두 확보
- **특징**: 대부분 요청을 로컬에서 빠르게 처리하되, 주기적으로 분산 상태와 동기화
- **목표**: 단일 서버의 성능 + 분산 시스템의 일관성

### 아키텍처 구조
```
┌─────────────────────────────────────────────────────┐
│                 HybridRateLimiter                   │
├─────────────────┬───────────────────────────────────┤
│ 로컬 레이어      │ 분산 레이어                       │
│ Token Bucket    │ Distributed Backend              │
│ (~1μs)          │ (Redis/DB ~1ms)                  │
├─────────────────┼───────────────────────────────────┤
│ • 빠른 응답     │ • 전역 일관성                     │
│ • 높은 처리량   │ • 영속성                         │
│ • 내결함성      │ • 정확한 제한                     │
└─────────────────┴───────────────────────────────────┘
```

### 선택 이유
1. **최적의 성능**: 로컬 처리로 마이크로초 단위 응답 시간
2. **분산 일관성**: 주기적 동기화로 전역 제한 유지
3. **내결함성**: 분산 백엔드 장애 시에도 서비스 지속
4. **실용성**: 실제 프로덕션 환경의 복잡한 요구사항 만족

## 핵심 구현 사항

### 1. 이중 레이어 아키텍처
```kotlin
// 로컬 레이어: Token Bucket (고성능)
class LocalTokenBucketState {
    val tokens: AtomicReference<Double>
    val lastRefillMillis: AtomicLong
    val lastSyncMillis: AtomicLong
}

// 분산 레이어: Backend 추상화 (일관성)
interface DistributedBackend {
    fun checkAt(userId, rule, now): Decision?
    fun syncState(userId, rule, localCount, now)
    fun isHealthy(): Boolean
}
```

### 2. 하이브리드 결정 로직
```kotlin
override fun checkAt(userId: UserId, rule: RateLimitRule, now: Instant): Decision {
    // 1. 로컬 검사 (항상 수행)
    val localDecision = localState.checkLocal(nowMs, capacity, refillRate)
    
    // 2. 분산 검사 (조건부 수행)
    val distributedDecision = if (shouldCheckDistributed()) {
        distributedBackend.checkAt(userId, rule, now)
    } else null
    
    // 3. 결정 통합 (보수적 정책)
    return combineDecisions(localDecision, distributedDecision)
}
```

### 3. 원자적 로컬 상태 관리
```kotlin
// Compare-And-Set (CAS) 루프를 통한 lock-free 업데이트
private fun updateTokensAtomically(): Double {
    while (true) {
        val currentTokens = tokens.get()
        val newTokens = calculateNewTokens(currentTokens)
        if (tokens.compareAndSet(currentTokens, newTokens)) {
            return newTokens
        }
        // CAS 실패 시 재시도
    }
}
```

### 4. 비동기 동기화 전략
```kotlin
// 백그라운드 동기화 (메인 처리 스레드 블록 없음)
private fun triggerAsyncSync() {
    executor.execute {
        distributedBackend.syncState(userId, rule, localCount, now)
        localState.markSynced(nowMs)
    }
}
```

## 동시성 처리 전략

### 1. Lock-Free 로컬 상태
- **AtomicReference**: 토큰 수의 원자적 업데이트
- **AtomicLong**: 시간 정보의 원자적 업데이트  
- **CAS 루프**: 락 없는 동시성 제어
- **성능**: 락 경합 없이 높은 처리량 달성

### 2. 분산 백엔드 격리
- **별도 스레드풀**: 분산 연산이 로컬 처리에 영향 없음
- **비동기 처리**: 네트워크 지연이 응답 시간에 영향 없음
- **Fail-Fast**: 분산 백엔드 오류 시 즉시 로컬로 복귀

### 3. 동기화 간격 최적화
- **적응적 간격**: 부하에 따라 동기화 주기 조정 가능
- **배치 처리**: 여러 사용자를 한 번에 동기화
- **지연 동기화**: 필요시에만 동기화 수행

## 성능 특성 분석

### 1. 응답 시간 분포
```
로컬 처리 (99%): 1-3μs
분산 확인 (1%): 100-1000μs (네트워크에 따라)
평균 응답 시간: ~1-5μs (대부분 로컬)
```

### 2. 처리량 특성
```
순수 로컬: ~1M req/sec per core
하이브리드: ~800K req/sec per core (동기화 오버헤드)
Redis 전용: ~50K req/sec per core (네트워크 병목)
```

### 3. 메모리 사용량
```
로컬 상태: ~80 bytes per user (AtomicReference 오버헤드)
분산 백엔드: 별도 관리 (Redis 등)
총 메모리: 로컬 방식과 비슷하지만 더 정확
```

## 일관성 모델 분석

### 1. 일관성 수준
- **로컬 일관성**: 단일 서버 내 완벽 일관성
- **최종 일관성**: 분산 환경에서 최종적 일관성 수렴
- **근사 정확도**: 동기화 간격에 따라 90-99% 정확도

### 2. 일관성 vs 성능 Trade-off
```
동기화 간격    정확도    로컬 성능    분산 부하
1초          99%      95%         높음
30초         95%      99%         중간  
300초        90%      99.9%       낮음
```

### 3. 경계 조건 처리
- **동기화 시점**: 새 윈도우 시작 시 강제 동기화
- **백엔드 복구**: 분산 백엔드 복구 시 점진적 일관성 회복
- **클럭 스큐**: 로컬-분산 간 시간 차이 보정

## 장점과 단점 분석

### 장점
1. **극고성능**: 로컬 처리로 마이크로초 단위 응답
2. **분산 지원**: 여러 서버 간 상태 동기화
3. **내결함성**: 분산 백엔드 장애에도 서비스 지속
4. **적응성**: 네트워크 상황에 따라 자동 조절
5. **확장성**: 로컬+분산의 장점 결합

### 단점
1. **복잡성**: 두 시스템 조합으로 인한 높은 복잡도
2. **근사 정확도**: 완벽한 분산 일관성은 아님
3. **메모리 사용**: 로컬 상태 + 분산 상태 이중 관리
4. **디버깅 어려움**: 로컬-분산 상태 불일치 추적 복잡
5. **설정 복잡성**: 동기화 간격, 백엔드 설정 등 많은 튜닝 포인트

## 구현 세부사항

### 1. 결정 통합 정책
```kotlin
// 보수적 정책: 둘 중 하나라도 거부하면 거부
private fun combineDecisions(local: Decision, distributed: Decision?): Decision {
    return when {
        distributed == null -> local
        local.allowed && distributed.allowed -> allow()
        else -> deny()
    }
}
```

### 2. 백그라운드 동기화
```kotlin
// 주기적 전체 동기화 + 개별 동기화
executor.scheduleAtFixedRate(::performBackgroundSync, 30_000, 30_000, MILLISECONDS)

// 개별 동기화 트리거
if (localState.needsSync(syncIntervalMs, nowMs)) {
    triggerAsyncSync(userId, rule, localState, nowMs)
}
```

### 3. 상태 정리 전략
```kotlin
// 비활성 상태 정리 (메모리 누수 방지)
val staleThresholdMs = syncIntervalMs * 10
localStates.entries.removeIf { (key, state) ->
    (nowMs - state.lastSyncMillis.get()) >= staleThresholdMs
}
```

## 실제 프로덕션 고려사항

### 1. 모니터링 지표
- **로컬 적중률**: 분산 검사 없이 처리된 비율
- **동기화 빈도**: 분산 백엔드 호출 빈도
- **일관성 지연**: 로컬-분산 간 상태 차이
- **백엔드 건강도**: 분산 백엔드 가용성

### 2. 설정 최적화
```kotlin
// 동기화 간격: 정확도 vs 성능 균형
val syncIntervalMs = when (accuracy) {
    HIGH -> 5_000L      // 5초 - 높은 정확도
    MEDIUM -> 30_000L   // 30초 - 균형
    LOW -> 300_000L     // 5분 - 높은 성능
}
```

### 3. 장애 대응
- **Circuit Breaker**: 분산 백엔드 장애 시 자동 차단
- **Health Check**: 주기적 분산 백엔드 상태 확인  
- **Graceful Degradation**: 점진적 성능 저하로 서비스 유지

## 테스트 시나리오

### 1. 성능 테스트
- 로컬 전용 vs 하이브리드 응답 시간 비교
- 다양한 동기화 간격에서 처리량 측정
- 백그라운드 동기화 오버헤드 측정

### 2. 일관성 테스트
- 다중 서버에서 동시 요청 처리 정확도
- 분산 백엔드 장애 시 일관성 유지
- 동기화 간격에 따른 정확도 변화

### 3. 장애 테스트
- 분산 백엔드 완전 장애 시나리오
- 네트워크 분할 상황에서 동작
- 로컬 메모리 부족 시 처리

## 다른 방식과의 비교

| 측면 | 순수 로컬 | 순수 분산 | 하이브리드 |
|------|----------|----------|----------|
| **응답시간** | 최고 (~1μs) | 중간 (~1ms) | 높음 (~5μs) |
| **정확도** | 낮음 (분산X) | 높음 (100%) | 중간 (95%) |
| **확장성** | 제한적 | 높음 | 높음 |
| **복잡도** | 낮음 | 중간 | 높음 |
| **내결함성** | 높음 | 낮음 | 매우 높음 |
| **운영복잡도** | 낮음 | 중간 | 높음 |

## 실제 사용 권장 상황

### 최적 사용 사례
- **고성능 + 분산 필요**: API Gateway, CDN Edge
- **내결함성 중요**: 금융, 결제 시스템
- **가변 부하**: 트래픽이 급변하는 서비스
- **복합 요구사항**: 성능과 일관성 모두 중요

### 부적절한 사용 사례
- **완벽한 정확도 필수**: 규제, 보안 시스템
- **단순함 우선**: 소규모 서비스
- **단일 서버**: 분산이 불필요한 환경
- **실시간 동기화**: 밀리초 단위 정확도 필요

## 확장 가능성

### v5.1: 적응적 동기화
```kotlin
// 부하에 따른 동적 동기화 간격 조정
val adaptiveSyncInterval = calculateOptimalInterval(
    currentLoad, accuracy, networkLatency
)
```

### v5.2: 지능형 결정 통합
```kotlin
// 머신러닝 기반 로컬-분산 결정 가중치 조정
val weight = mlModel.predictOptimalWeight(userPattern, systemState)
val decision = weightedCombine(localDecision, distributedDecision, weight)
```

### v5.3: 멀티 백엔드
```kotlin
// 여러 분산 백엔드 조합 (Redis + DB + API)
val backends = listOf(redisBackend, dbBackend, apiBackend)
val decisions = backends.parallelMap { it.checkAt(userId, rule, now) }
val consensus = consensusAlgorithm.decide(decisions)
```
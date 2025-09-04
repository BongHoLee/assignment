package pay.assignment.hybrid_local_redis

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Rate Limiter 인터페이스
 * 사용자별 API 호출 횟수를 제한하는 기능을 정의
 */
interface RateLimiter {
    /**
     * 현재 시각을 기준으로 요청을 검사
     * @param userId 사용자 식별자
     * @param rule 제한 규칙 (최대 요청 수, 시간 창)
     * @return 허용 여부와 메타데이터를 포함한 결정
     */
    fun check(userId: UserId, rule: RateLimitRule): Decision
    
    /**
     * 지정된 시각을 기준으로 요청을 검사 (테스트용)
     * @param userId 사용자 식별자
     * @param rule 제한 규칙
     * @param now 검사 기준 시각
     * @return 허용 여부와 메타데이터를 포함한 결정
     */
    fun checkAt(userId: UserId, rule: RateLimitRule, now: Instant): Decision
}

/**
 * 사용자 식별자를 감싸는 값 객체
 * @param id 사용자 식별 문자열 (헤더, 세션, IP 등)
 */
data class UserId(val id: String)

/**
 * Rate Limit 규칙을 정의하는 데이터 클래스
 * @param maxRequest 시간 창 내 최대 허용 요청 수
 * @param timeWindowSeconds 시간 창 크기 (초 단위)
 */
data class RateLimitRule(
    val maxRequest: Int,
    val timeWindowSeconds: Int
)

/**
 * Rate Limit 검사 결과
 * @param allowed 요청 허용 여부
 * @param remaining 남은 요청 수
 * @param retryAfterMillis 다음 요청까지 대기해야 할 시간 (밀리초)
 */
data class Decision(
    val allowed: Boolean,
    val remaining: Int,
    val retryAfterMillis: Long
)

/**
 * 사용자별 상태를 구분하기 위한 키
 * @param userId 사용자 식별자
 * @param windowSeconds 시간 창 크기 (초)
 */
data class StateKey(val userId: UserId, val windowSeconds: Int)

/**
 * 하이브리드 Rate Limiting을 위한 분산 백엔드 인터페이스
 * 
 * 로컬 캐시 실패 시 사용할 분산 저장소의 추상화
 * Redis, Database, 외부 API 등 다양한 구현체 가능
 */
interface DistributedBackend {
    /**
     * 분산 저장소에서 Rate Limit 검사 수행
     * @param userId 사용자 식별자
     * @param rule 제한 규칙
     * @param now 검사 기준 시각
     * @return 허용/거부 결정, 연결 실패 시 null
     */
    fun checkAt(userId: UserId, rule: RateLimitRule, now: Instant): Decision?
    
    /**
     * 로컬 캐시와 분산 저장소 간 동기화
     * @param userId 사용자 식별자
     * @param rule 제한 규칙
     * @param localCount 로컬 카운트
     * @param now 현재 시각
     */
    fun syncState(userId: UserId, rule: RateLimitRule, localCount: Int, now: Instant)
    
    /**
     * 분산 백엔드의 상태 확인
     * @return 사용 가능하면 true
     */
    fun isHealthy(): Boolean
}

/**
 * Token Bucket 기반 로컬 상태 관리
 * 
 * 하이브리드 시스템의 로컬 레이어를 담당
 * 높은 성능과 낮은 지연 시간을 위해 메모리 기반 Token Bucket 사용
 * 
 * @param tokens 현재 토큰 수 (원자적 업데이트 위해 AtomicReference 사용)
 * @param lastRefillMillis 마지막 토큰 충전 시각
 * @param lastSyncMillis 마지막 분산 동기화 시각
 */
data class LocalTokenBucketState(
    val tokens: AtomicReference<Double> = AtomicReference(0.0),
    val lastRefillMillis: AtomicLong = AtomicLong(0L),
    val lastSyncMillis: AtomicLong = AtomicLong(0L)
) {
    companion object {
        /**
         * 초기 상태 생성 (버킷을 가득 채운 상태로 시작)
         * @param capacity 버킷 용량
         * @param nowMs 현재 시각 (밀리초)
         * @return 초기화된 로컬 상태
         */
        fun init(capacity: Double, nowMs: Long): LocalTokenBucketState {
            return LocalTokenBucketState().apply {
                tokens.set(capacity)
                lastRefillMillis.set(nowMs)
                lastSyncMillis.set(nowMs)
            }
        }
    }

    /**
     * 로컬 Token Bucket 기반 제한 검사
     * 
     * 처리 흐름:
     * 1. 토큰 충전 (시간 경과에 따라)
     * 2. 토큰 가용성 확인
     * 3. 허용 시 토큰 소비
     * 4. 결과 반환
     * 
     * @param nowMs 현재 시각
     * @param capacity 버킷 용량
     * @param refillRatePerSecond 초당 토큰 충전 속도
     * @return 허용/거부 결정과 메타데이터
     */
    fun checkLocal(nowMs: Long, capacity: Int, refillRatePerSecond: Double): Decision {
        // 원자적 토큰 업데이트 (compare-and-set 루프)
        val currentTokens = updateTokensAtomically(nowMs, capacity, refillRatePerSecond)
        
        // 토큰 가용성 확인 및 소비
        val allowed = if (currentTokens >= 1.0) {
            // 토큰 소비 시도 (원자적)
            val newTokens = currentTokens - 1.0
            if (tokens.compareAndSet(currentTokens, newTokens)) {
                true
            } else {
                // CAS 실패 시 재시도 없이 거부 (성능 우선)
                false
            }
        } else {
            false
        }

        return createDecision(allowed, capacity, currentTokens)
    }

    /**
     * 원자적 토큰 업데이트 (토큰 충전 로직)
     * 
     * Compare-And-Set (CAS) 루프를 사용하여 동시성 보장
     * 여러 스레드가 동시에 토큰을 업데이트해도 안전
     * 
     * @param nowMs 현재 시각
     * @param capacity 버킷 최대 용량
     * @param refillRatePerSecond 초당 충전 속도
     * @return 업데이트된 토큰 수
     */
    private fun updateTokensAtomically(nowMs: Long, capacity: Int, refillRatePerSecond: Double): Double {
        while (true) {
            val currentTokens = tokens.get()
            val lastRefill = lastRefillMillis.get()
            val elapsedMs = nowMs - lastRefill
            
            if (elapsedMs <= 0) return currentTokens
            
            // 새로운 토큰 수 계산
            val tokensToAdd = (elapsedMs / 1000.0) * refillRatePerSecond
            val newTokens = (currentTokens + tokensToAdd).coerceAtMost(capacity.toDouble())
            
            // 원자적 업데이트 시도
            if (tokens.compareAndSet(currentTokens, newTokens) && 
                lastRefillMillis.compareAndSet(lastRefill, nowMs)) {
                return newTokens
            }
            // CAS 실패 시 재시도
        }
    }

    /**
     * 결정 객체 생성
     * @param allowed 허용 여부
     * @param capacity 버킷 용량
     * @param currentTokens 현재 토큰 수
     * @return Decision 객체
     */
    private fun createDecision(allowed: Boolean, capacity: Int, currentTokens: Double): Decision {
        val remaining = currentTokens.toInt().coerceAtLeast(0)
        val retryAfter = if (allowed) 0L else 1000L
        return Decision(allowed, remaining, retryAfter)
    }

    /**
     * 동기화 필요 여부 확인
     * @param syncIntervalMs 동기화 간격 (밀리초)
     * @param nowMs 현재 시각
     * @return 동기화가 필요하면 true
     */
    fun needsSync(syncIntervalMs: Long, nowMs: Long): Boolean {
        return (nowMs - lastSyncMillis.get()) >= syncIntervalMs
    }

    /**
     * 동기화 시각 업데이트
     * @param nowMs 현재 시각
     */
    fun markSynced(nowMs: Long) {
        lastSyncMillis.set(nowMs)
    }

    /**
     * 현재 토큰 수 조회 (모니터링용)
     * @return 현재 보유 토큰 수
     */
    fun getCurrentTokens(): Double = tokens.get()
}

/**
 * 간단한 분산 백엔드 구현 (데모용)
 * 
 * 실제 프로덕션에서는 Redis, Database 등을 사용
 * 여기서는 Fixed Window 기반의 간단한 분산 시뮬레이션
 */
class SimpleDistributedBackend(
    private val clock: Clock = Clock.systemUTC()
) : DistributedBackend {
    
    // 전역 상태 시뮬레이션 (실제로는 Redis 등 외부 저장소)
    private val globalCounters = ConcurrentHashMap<String, AtomicLong>()
    private val windowStarts = ConcurrentHashMap<String, AtomicLong>()
    private var healthy = true

    override fun checkAt(userId: UserId, rule: RateLimitRule, now: Instant): Decision? {
        if (!healthy) return null
        
        try {
            val nowMs = now.toEpochMilli()
            val windowMs = rule.timeWindowSeconds * 1000L
            val windowStartMs = (nowMs / windowMs) * windowMs
            val key = "${userId.id}:${rule.timeWindowSeconds}"
            
            // 윈도우 시작점 업데이트
            val currentWindowStart = windowStarts.computeIfAbsent(key) { AtomicLong(windowStartMs) }
            if (windowStartMs > currentWindowStart.get()) {
                currentWindowStart.set(windowStartMs)
                globalCounters[key]?.set(0L)
            }
            
            // 카운터 증가 및 검사
            val currentCount = globalCounters.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
            val allowed = currentCount <= rule.maxRequest
            val remaining = (rule.maxRequest - currentCount.toInt()).coerceAtLeast(0)
            
            return Decision(allowed, remaining, if (allowed) 0L else 1000L)
            
        } catch (e: Exception) {
            // 분산 백엔드 오류 시 null 반환
            return null
        }
    }

    override fun syncState(userId: UserId, rule: RateLimitRule, localCount: Int, now: Instant) {
        // 실제 구현에서는 로컬과 분산 상태를 동기화
        // 여기서는 간단히 로깅만 수행
    }

    override fun isHealthy(): Boolean = healthy
    
    /**
     * 백엔드 상태 설정 (테스트용)
     * @param healthy 건강 상태
     */
    fun setHealthy(healthy: Boolean) {
        this.healthy = healthy
    }
}

/**
 * 하이브리드 Rate Limiter - 로컬 캐시 + 분산 백엔드 결합
 * 
 * 핵심 아키텍처:
 * 1. **로컬 우선**: 대부분 요청을 로컬 Token Bucket으로 빠르게 처리
 * 2. **분산 동기화**: 주기적으로 분산 백엔드와 상태 동기화
 * 3. **Graceful Degradation**: 분산 백엔드 장애 시 로컬만으로 계속 동작
 * 4. **최적화된 성능**: 로컬 처리로 마이크로초 단위 응답 시간
 * 
 * 특징:
 * + 높은 성능: 로컬 처리로 극저지연 (~1-3μs)
 * + 분산 일관성: 주기적 동기화로 전역 제한 유지  
 * + 내결함성: 분산 백엔드 장애에도 서비스 지속
 * + 확장성: 로컬+분산 조합으로 최적 확장
 * - 복잡성: 두 시스템의 조합으로 인한 복잡도
 * - 근사 정확도: 완벽한 분산 일관성은 아님
 * 
 * 사용 시나리오:
 * - 높은 처리량이 필요하면서도 분산 일관성이 중요한 경우
 * - 네트워크 지연에 민감한 실시간 서비스
 * - 분산 백엔드의 안정성을 보장할 수 없는 환경
 * 
 * @param distributedBackend 분산 저장소 (Redis, Database 등)
 * @param clock 시간 제공자 (테스트용 주입 가능)
 * @param syncIntervalMs 분산 동기화 간격 (기본 30초)
 * @param localStates 로컬 Token Bucket 상태 저장소
 * @param executor 백그라운드 동기화용 스케줄러
 */
class HybridRateLimiter(
    private val distributedBackend: DistributedBackend = SimpleDistributedBackend(),
    private val clock: Clock = Clock.systemUTC(),
    private val syncIntervalMs: Long = 30_000L, // 30초
    private val localStates: ConcurrentHashMap<StateKey, LocalTokenBucketState> = ConcurrentHashMap(),
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "hybrid-rate-limiter-sync").apply { isDaemon = true }
    }
) : RateLimiter {

    init {
        // 백그라운드 동기화 작업 시작 (주기적으로 분산 백엔드와 동기화)
        executor.scheduleAtFixedRate(
            ::performBackgroundSync,
            syncIntervalMs,
            syncIntervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    /**
     * 현재 시각 기준으로 Rate Limit 검사
     * @param userId 사용자 식별자
     * @param rule 제한 규칙
     * @return 허용/거부 결정
     */
    override fun check(userId: UserId, rule: RateLimitRule): Decision {
        return checkAt(userId, rule, Instant.now(clock))
    }

    /**
     * 지정된 시각 기준으로 하이브리드 Rate Limit 검사
     * 
     * 처리 전략:
     * 1. **로컬 우선**: 먼저 로컬 Token Bucket으로 빠른 검사
     * 2. **분산 확인**: 분산 백엔드가 건강하고 동기화가 필요한 경우 추가 검사
     * 3. **결정 통합**: 두 결과를 조합하여 최종 허용/거부 결정
     * 4. **비동기 동기화**: 백그라운드에서 상태 동기화 수행
     * 
     * @param userId 사용자 식별자
     * @param rule 제한 규칙 (maxRequest/timeWindowSeconds)
     * @param now 검사 기준 시각
     * @return 허용/거부 결정과 메타데이터
     */
    override fun checkAt(userId: UserId, rule: RateLimitRule, now: Instant): Decision {
        val nowMs = now.toEpochMilli()
        val key = StateKey(userId, rule.timeWindowSeconds)
        val refillRate = rule.maxRequest.toDouble() / rule.timeWindowSeconds.toDouble()
        
        // 1. 로컬 상태 조회 또는 생성
        val localState = localStates.computeIfAbsent(key) {
            LocalTokenBucketState.init(rule.maxRequest.toDouble(), nowMs)
        }
        
        // 2. 로컬 Token Bucket 검사 (항상 수행, 고성능)
        val localDecision = localState.checkLocal(nowMs, rule.maxRequest, refillRate)
        
        // 3. 분산 백엔드 검사 (조건부 수행)
        val distributedDecision = if (shouldCheckDistributed(localState, nowMs)) {
            distributedBackend.checkAt(userId, rule, now)
        } else null
        
        // 4. 결정 통합: 보수적 접근 (둘 중 하나라도 거부하면 거부)
        val finalDecision = combineDecisions(localDecision, distributedDecision)
        
        // 5. 동기화 필요시 백그라운드 동기화 트리거
        if (localState.needsSync(syncIntervalMs, nowMs)) {
            triggerAsyncSync(userId, rule, localState, nowMs)
        }
        
        return finalDecision
    }

    /**
     * 분산 백엔드 검사 필요 여부 판단
     * 
     * 조건:
     * - 분산 백엔드가 건강한 상태
     * - 동기화 간격이 지났거나 로컬에서 거부된 경우
     * 
     * @param localState 로컬 상태
     * @param nowMs 현재 시각
     * @return 분산 검사가 필요하면 true
     */
    private fun shouldCheckDistributed(localState: LocalTokenBucketState, nowMs: Long): Boolean {
        return distributedBackend.isHealthy() && localState.needsSync(syncIntervalMs, nowMs)
    }

    /**
     * 로컬과 분산 결정을 통합
     * 
     * 정책: 보수적 접근 (Conservative Policy)
     * - 둘 다 허용 → 허용
     * - 둘 중 하나라도 거부 → 거부  
     * - 분산 결정 없음 → 로컬 결정 사용
     * 
     * @param localDecision 로컬 Token Bucket 결정
     * @param distributedDecision 분산 백엔드 결정 (null 가능)
     * @return 통합된 최종 결정
     */
    private fun combineDecisions(localDecision: Decision, distributedDecision: Decision?): Decision {
        return when {
            distributedDecision == null -> localDecision
            localDecision.allowed && distributedDecision.allowed -> {
                // 둘 다 허용: 더 엄격한 제한 사용
                Decision(
                    allowed = true,
                    remaining = minOf(localDecision.remaining, distributedDecision.remaining),
                    retryAfterMillis = 0L
                )
            }
            else -> {
                // 둘 중 하나라도 거부: 더 긴 대기 시간 사용
                Decision(
                    allowed = false,
                    remaining = 0,
                    retryAfterMillis = maxOf(localDecision.retryAfterMillis, distributedDecision.retryAfterMillis)
                )
            }
        }
    }

    /**
     * 비동기 동기화 작업 트리거
     * 
     * 백그라운드에서 로컬과 분산 상태를 동기화
     * 메인 요청 처리 스레드를 블록하지 않음
     * 
     * @param userId 사용자 식별자
     * @param rule 제한 규칙
     * @param localState 로컬 상태
     * @param nowMs 현재 시각
     */
    private fun triggerAsyncSync(
        userId: UserId,
        rule: RateLimitRule,
        localState: LocalTokenBucketState,
        nowMs: Long
    ) {
        executor.execute {
            try {
                val currentTokens = localState.getCurrentTokens().toInt()
                distributedBackend.syncState(userId, rule, currentTokens, Instant.ofEpochMilli(nowMs))
                localState.markSynced(nowMs)
            } catch (e: Exception) {
                // 동기화 실패는 무시 (로컬 상태로 계속 동작)
            }
        }
    }

    /**
     * 백그라운드 동기화 작업 (주기적 실행)
     * 
     * 모든 활성 사용자에 대해 정기적으로 상태 동기화
     * 장기간 비활성 상태 정리도 함께 수행
     */
    private fun performBackgroundSync() {
        val nowMs = clock.millis()
        val staleThresholdMs = syncIntervalMs * 10 // 10배 주기 이후 정리
        
        localStates.entries.removeIf { (key, state) ->
            val lastSync = state.lastSyncMillis.get()
            
            if ((nowMs - lastSync) >= staleThresholdMs) {
                // 장기간 비활성 상태 제거
                true
            } else if (state.needsSync(syncIntervalMs, nowMs)) {
                // 동기화 필요 상태 처리
                try {
                    val rule = RateLimitRule(3, key.windowSeconds) // 기본값 사용
                    val currentTokens = state.getCurrentTokens().toInt()
                    distributedBackend.syncState(key.userId, rule, currentTokens, Instant.ofEpochMilli(nowMs))
                    state.markSynced(nowMs)
                } catch (e: Exception) {
                    // 동기화 실패 시 로그만 기록하고 상태 유지
                }
                false
            } else {
                false
            }
        }
    }

    /**
     * 특정 사용자의 로컬 상태 조회 (모니터링용)
     * @param userId 사용자 식별자
     * @param rule 제한 규칙
     * @return 현재 로컬 토큰 수, 상태가 없으면 null
     */
    fun getLocalTokens(userId: UserId, rule: RateLimitRule): Double? {
        val key = StateKey(userId, rule.timeWindowSeconds)
        return localStates[key]?.getCurrentTokens()
    }

    /**
     * 현재 활성 사용자 수 반환 (모니터링용)
     * @return 로컬 캐시에 저장된 사용자 수
     */
    fun getActiveUserCount(): Int = localStates.size

    /**
     * 분산 백엔드 상태 확인 (헬스체크용)
     * @return 분산 백엔드가 건강하면 true
     */
    fun isDistributedBackendHealthy(): Boolean = distributedBackend.isHealthy()

    /**
     * 시스템 종료 시 리소스 정리
     * 백그라운드 스케줄러를 안전하게 종료
     */
    fun shutdown() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
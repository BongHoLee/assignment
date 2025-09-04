package pay.assignment.sliding_window_log

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

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
 * Sliding Window Log 알고리즘을 위한 사용자별 상태
 * 
 * Sliding Window Log 개념:
 * - 각 요청의 정확한 타임스탬프를 기록
 * - 슬라이딩 윈도우 내의 요청 수를 실시간 계산
 * - 윈도우가 시간에 따라 연속적으로 이동
 * - 가장 정확한 Rate Limiting 제공
 * 
 * 예시 (5초 윈도우, 3개 제한):
 * - 요청 시각: [1s, 2s, 3s, 6s]
 * - 7초 시점 검사: [2s, 3s, 6s] → 3개이므로 허용
 * - 8초 시점 검사: [3s, 6s] → 2개이므로 허용
 * 
 * @param requestLog 요청 시각들을 저장하는 큐 (FIFO)
 * @param lastCleanupMillis 마지막 정리 작업 시각 (TTL 관리용)
 */
data class SlidingWindowLogState(
    private val requestLog: ConcurrentLinkedQueue<Long> = ConcurrentLinkedQueue(),
    var lastCleanupMillis: Long
) {
    companion object {
        /**
         * 초기 상태 생성
         * @param nowMs 현재 시각 (밀리초)
         * @return 빈 로그를 가진 초기 상태
         */
        fun init(nowMs: Long) = SlidingWindowLogState(lastCleanupMillis = nowMs)
    }

    /**
     * 요청에 대한 허용/거부 결정을 수행
     * 
     * 처리 순서:
     * 1. 윈도우 범위를 벗어난 오래된 요청들 제거
     * 2. 현재 윈도우 내 요청 수 확인
     * 3. 제한 범위 내이면 새 요청 기록 후 허용
     * 4. 제한 초과면 거부
     * 
     * @param nowMs 현재 시각 (밀리초)
     * @param windowMs 윈도우 크기 (밀리초)
     * @param limit 최대 허용 요청 수
     * @return 허용/거부 결정과 메타데이터
     */
    fun decide(nowMs: Long, windowMs: Long, limit: Int): Decision {
        // 1. 윈도우 범위를 벗어난 오래된 요청들 제거
        cleanupExpiredRequests(nowMs, windowMs)
        
        // 2. 현재 윈도우 내 요청 수 확인
        val currentRequestCount = requestLog.size
        
        // 3. 제한 검사 및 처리
        val allowed = currentRequestCount < limit
        if (allowed) {
            // 허용 시: 현재 요청 시각을 로그에 추가
            requestLog.offer(nowMs)
        }
        
        // 4. 결과 생성 및 반환
        return createDecision(allowed, limit, currentRequestCount, nowMs, windowMs)
    }

    /**
     * 윈도우 범위를 벗어난 오래된 요청들을 제거
     * 
     * 제거 기준: 현재 시각 - 윈도우 크기 보다 이전의 요청들
     * 예시: 현재 7초, 윈도우 5초 → 2초 이전 요청들 제거
     * 
     * @param nowMs 현재 시각
     * @param windowMs 윈도우 크기 (밀리초)
     */
    private fun cleanupExpiredRequests(nowMs: Long, windowMs: Long) {
        val windowStartMs = nowMs - windowMs
        
        // 큐의 앞쪽부터 순차적으로 확인하여 오래된 요청들 제거
        // peek()으로 확인 후 poll()으로 제거하는 방식으로 안전하게 처리
        while (requestLog.peek()?.let { it < windowStartMs } == true) {
            requestLog.poll()
        }
        
        // 정리 작업 시각 업데이트 (TTL 관리용)
        lastCleanupMillis = nowMs
    }

    /**
     * 결정 객체 생성
     * 
     * @param allowed 요청 허용 여부
     * @param limit 최대 허용 요청 수
     * @param currentCount 현재 윈도우 내 요청 수
     * @param nowMs 현재 시각
     * @param windowMs 윈도우 크기
     * @return Decision 객체
     */
    private fun createDecision(
        allowed: Boolean, 
        limit: Int, 
        currentCount: Int, 
        nowMs: Long, 
        windowMs: Long
    ): Decision {
        // 허용된 경우 남은 요청 수 계산 (새 요청이 추가되었으므로 +1)
        val remaining = if (allowed) limit - (currentCount + 1) else limit - currentCount
        
        // 재시도 대기 시간 계산
        val retryAfter = calculateRetryAfter(allowed, nowMs, windowMs)
        
        return Decision(allowed, remaining.coerceAtLeast(0), retryAfter)
    }

    /**
     * 차단된 경우 재시도까지의 대기 시간 계산
     * 
     * 계산 방식:
     * - 허용된 경우: 0ms (즉시 다음 요청 가능)
     * - 차단된 경우: 가장 오래된 요청이 윈도우를 벗어날 때까지의 시간
     * 
     * 예시: 가장 오래된 요청이 3초, 현재 7초, 윈도우 5초
     * → (3 + 5) - 7 = 1초 후 재시도 가능
     * 
     * @param allowed 허용 여부
     * @param nowMs 현재 시각
     * @param windowMs 윈도우 크기
     * @return 밀리초 단위 대기 시간
     */
    private fun calculateRetryAfter(allowed: Boolean, nowMs: Long, windowMs: Long): Long {
        if (allowed) return 0L
        
        // 가장 오래된 요청의 시각 조회
        val oldestRequestMs = requestLog.peek()
        
        return if (oldestRequestMs != null) {
            // 가장 오래된 요청이 윈도우를 벗어날 때까지의 시간
            ((oldestRequestMs + windowMs) - nowMs).coerceAtLeast(1L)
        } else {
            // 로그가 비어있으면 1초 후 재시도 (안전장치)
            1000L
        }
    }

    /**
     * 현재 로그에 저장된 요청 수 반환 (디버깅/모니터링 용)
     * @return 로그 내 요청 수
     */
    fun getRequestCount(): Int = requestLog.size
}

/**
 * Sliding Window Log 알고리즘을 구현한 Rate Limiter
 * 
 * 특징:
 * - 최고 정확도: 각 요청의 정확한 시각 기록
 * - 메모리 사용량: 사용자당 요청 수에 비례 (최대 limit * 8 bytes)
 * - 시간 복잡도: O(n) (n = 윈도우 내 요청 수)
 * - 동시성: ConcurrentLinkedQueue + synchronized로 보장
 * 
 * Trade-off:
 * + 가장 정확한 Rate Limiting
 * + 버스트와 지속적 요청 모두 정확히 제한
 * - 메모리 사용량이 요청 수에 비례하여 증가
 * - 요청 처리 시 O(n) 시간 복잡도
 * 
 * @param clock 시간 제공자 (테스트용 주입 가능)
 * @param states 사용자별 슬라이딩 윈도우 로그 저장소
 */
class SlidingWindowLogLimiter(
    private val clock: Clock = Clock.systemUTC(),
    private val states: ConcurrentHashMap<StateKey, SlidingWindowLogState> = ConcurrentHashMap()
) : RateLimiter {

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
     * 지정된 시각 기준으로 Rate Limit 검사
     * 
     * 처리 흐름:
     * 1. 윈도우 크기를 밀리초로 변환
     * 2. 사용자별 상태 조회 또는 생성
     * 3. 동기화된 블록에서 로그 기반 검사 수행
     * 
     * @param userId 사용자 식별자
     * @param rule 제한 규칙 (maxRequest/timeWindowSeconds)
     * @param now 검사 기준 시각
     * @return 허용/거부 결정과 메타데이터
     */
    override fun checkAt(userId: UserId, rule: RateLimitRule, now: Instant): Decision {
        val nowMs = now.toEpochMilli()
        val windowMs = rule.timeWindowSeconds * 1000L
        val key = StateKey(userId, rule.timeWindowSeconds)

        // 사용자별 상태 조회 또는 초기화
        val state = states.computeIfAbsent(key) { 
            SlidingWindowLogState.init(nowMs) 
        }

        // 개별 사용자 상태에 대한 동기화
        // ConcurrentLinkedQueue는 thread-safe하지만 복합 연산을 위해 동기화 필요
        return synchronized(state) {
            state.decide(nowMs, windowMs, rule.maxRequest)
        }
    }

    /**
     * 비활성 사용자 상태 정리 (메모리 누수 방지)
     * 
     * 정리 기준: 마지막 정리 작업 시점으로부터 일정 시간 경과
     * Sliding Window Log는 요청 로그를 저장하므로 적극적인 정리가 중요
     * 
     * @param idleMillis 비활성 기준 시간 (기본 10분)
     * @param now 현재 시각 (테스트용)
     */
    fun cleanupExpired(
        idleMillis: Long = 10 * 60 * 1000L,
        now: Instant = Instant.now(clock)
    ) {
        val nowMs = now.toEpochMilli()
        // 비활성 상태를 가진 사용자들을 일괄 제거
        states.entries.removeIf { (_, state) -> 
            (nowMs - state.lastCleanupMillis) >= idleMillis 
        }
    }

    /**
     * 현재 저장된 전체 사용자 수 반환 (모니터링용)
     * @return 활성 사용자 수
     */
    fun getActiveUserCount(): Int = states.size

    /**
     * 특정 사용자의 현재 요청 수 반환 (디버깅용)
     * @param userId 사용자 식별자
     * @param rule 제한 규칙
     * @return 현재 윈도우 내 요청 수, 사용자 없으면 0
     */
    fun getUserRequestCount(userId: UserId, rule: RateLimitRule): Int {
        val key = StateKey(userId, rule.timeWindowSeconds)
        return states[key]?.getRequestCount() ?: 0
    }
}
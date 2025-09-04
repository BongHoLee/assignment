package pay.assignment.token_bucket

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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
 * @param remaining 남은 요청 수 (토큰 수)
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
 * Token Bucket 알고리즘을 위한 사용자별 상태
 * 
 * Token Bucket 개념:
 * - 토큰이 일정한 속도로 버킷에 채워짐
 * - 요청 시마다 토큰 1개를 소비
 * - 토큰이 없으면 요청 차단
 * - 버킷 크기 = 최대 버스트 허용량
 * 
 * @param tokens 현재 보유 토큰 수 (소수점 허용으로 정밀한 계산)
 * @param lastRefillMillis 마지막으로 토큰을 충전한 시각 (밀리초)
 */
data class TokenBucketState(
    var tokens: Double,
    var lastRefillMillis: Long
) {
    companion object {
        /**
         * 초기 상태 생성 (버킷을 가득 채운 상태로 시작)
         * @param capacity 버킷 용량 (최대 토큰 수)
         * @param nowMs 현재 시각 (밀리초)
         */
        fun init(capacity: Double, nowMs: Long) = TokenBucketState(capacity, nowMs)
    }

    /**
     * 요청에 대한 허용/거부 결정을 수행
     * 
     * 처리 순서:
     * 1. 경과 시간에 따라 토큰 충전
     * 2. 토큰 가용성 확인
     * 3. 허용 시 토큰 1개 소비
     * 4. 결과 및 메타데이터 반환
     * 
     * @param nowMs 현재 시각 (밀리초)
     * @param capacity 버킷 최대 용량
     * @param refillRatePerSecond 초당 토큰 충전 속도
     * @return 허용/거부 결정과 메타데이터
     */
    fun decide(nowMs: Long, capacity: Int, refillRatePerSecond: Double): Decision {
        // 1. 경과 시간에 따른 토큰 충전
        refillTokens(nowMs, capacity, refillRatePerSecond)
        
        // 2. 토큰 가용성 확인 (1개 이상 필요)
        val allowed = tokens >= 1.0
        
        // 3. 허용 시 토큰 소비
        if (allowed) {
            tokens -= 1.0
        }

        // 4. 결과 생성 및 반환
        return createDecision(allowed, capacity)
    }

    /**
     * 경과 시간에 따라 토큰을 충전
     * 
     * 충전 공식: 추가 토큰 = (경과 시간 / 1000) * 충전 속도
     * 예시: 1.5초 경과, 0.6개/초 충전 → 0.9개 토큰 추가
     * 
     * @param nowMs 현재 시각
     * @param capacity 버킷 최대 용량 (넘칠 수 없음)
     * @param refillRatePerSecond 초당 토큰 충전 속도
     */
    private fun refillTokens(nowMs: Long, capacity: Int, refillRatePerSecond: Double) {
        val elapsedMs = nowMs - lastRefillMillis
        if (elapsedMs > 0) {
            // 경과 시간에 비례한 토큰 계산
            val tokensToAdd = (elapsedMs / 1000.0) * refillRatePerSecond
            // 버킷 용량을 초과하지 않도록 제한
            tokens = (tokens + tokensToAdd).coerceAtMost(capacity.toDouble())
            lastRefillMillis = nowMs
        }
    }

    /**
     * 결정 객체 생성
     * @param allowed 요청 허용 여부
     * @param capacity 버킷 용량
     * @return Decision 객체
     */
    private fun createDecision(allowed: Boolean, capacity: Int): Decision {
        // 남은 토큰 수 계산 (음수 방지)
        val remaining = tokens.toInt().coerceAtLeast(0)
        // 재시도 대기 시간 계산
        val retryAfter = if (allowed) 0L else calculateRetryAfter()
        return Decision(allowed, remaining, retryAfter)
    }

    /**
     * 차단된 경우 재시도까지의 대기 시간 계산
     * 단순화된 구현: 토큰이 없으면 1초 대기
     * 실제로는 다음 토큰 충전 시간을 정확히 계산할 수 있음
     * 
     * @return 밀리초 단위 대기 시간
     */
    private fun calculateRetryAfter(): Long {
        return if (tokens <= 0) 1000L else 0L
    }
}

/**
 * Token Bucket 알고리즘을 구현한 Rate Limiter
 * 
 * 동시성 전략:
 * - ConcurrentHashMap: 사용자별 상태의 동시 접근 지원
 * - synchronized 블록: 개별 사용자 상태에 대한 원자적 연산 보장
 * 
 * 메모리 관리:
 * - 사용자별 상태를 메모리에 저장 (~72 bytes per user)
 * - cleanupExpired()로 비활성 사용자 정리 필요
 * 
 * @param clock 시간 제공자 (테스트용 주입 가능)
 * @param states 사용자별 토큰 버킷 상태 저장소
 */
class TokenBucketLimiter(
    private val clock: Clock = Clock.systemUTC(),
    private val states: ConcurrentHashMap<StateKey, TokenBucketState> = ConcurrentHashMap()
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
     * 1. 토큰 충전 속도 계산 (예: 3개/5초 = 0.6개/초)
     * 2. 사용자별 상태 조회 또는 생성
     * 3. 동기화된 블록에서 토큰 검사 및 소비
     * 
     * @param userId 사용자 식별자
     * @param rule 제한 규칙 (maxRequest/timeWindowSeconds)
     * @param now 검사 기준 시각
     * @return 허용/거부 결정과 메타데이터
     */
    override fun checkAt(userId: UserId, rule: RateLimitRule, now: Instant): Decision {
        val nowMs = now.toEpochMilli()
        val key = StateKey(userId, rule.timeWindowSeconds)
        
        // 토큰 충전 속도 계산 (예: 3개/5초 = 0.6개/초)
        val refillRate = rule.maxRequest.toDouble() / rule.timeWindowSeconds.toDouble()

        // 사용자별 상태 조회 또는 초기화 (처음 요청 시 버킷을 가득 채움)
        val state = states.computeIfAbsent(key) { 
            TokenBucketState.init(rule.maxRequest.toDouble(), nowMs) 
        }

        // 개별 사용자 상태에 대한 동기화
        // 다른 사용자의 요청은 블록되지 않음 (사용자별 독립적 처리)
        return synchronized(state) {
            state.decide(nowMs, rule.maxRequest, refillRate)
        }
    }

    /**
     * 비활성 사용자 상태 정리 (메모리 누수 방지)
     * 
     * 정리 기준: 마지막 접근 시점으로부터 일정 시간 경과
     * 실제 운영 환경에서는 @Scheduled로 주기적 실행 권장
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
            (nowMs - state.lastRefillMillis) >= idleMillis 
        }
    }
}
package pay.assignment.sliding_window_counter

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

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
 * Sliding Window Counter 알고리즘을 위한 사용자별 상태
 * 
 * Sliding Window Counter 개념:
 * - 시간 창을 여러 개의 작은 버킷(서브 윈도우)으로 분할
 * - 각 버킷에 해당 시간대의 요청 수를 카운트
 * - 현재 시점에서 윈도우에 포함되는 버킷들의 가중평균으로 근사 계산
 * - Sliding Window Log의 정확도와 Fixed Window의 효율성의 절충안
 * 
 * 예시 (5초 윈도우, 1초 버킷):
 * 버킷: [0-1s][1-2s][2-3s][3-4s][4-5s]
 * 카운트:[  2  ][  1  ][  0  ][  3  ][  1  ]
 * 
 * 2.5초 시점 검사 (윈도우: -2.5초 ~ 2.5초):
 * - 완전 포함: [0-1s][1-2s] → 2 + 1 = 3
 * - 부분 포함: [2-3s] → 0 * 0.5 = 0
 * - 총 근사 요청 수: 3 + 0 = 3개
 * 
 * @param buckets 각 서브윈도우별 요청 카운트 배열
 * @param bucketDurationMs 각 버킷이 담당하는 시간 범위 (밀리초)
 * @param windowStartMs 현재 윈도우의 시작 시각
 * @param lastUpdateMs 마지막 업데이트 시각 (TTL 관리용)
 */
data class SlidingWindowCounterState(
    private val buckets: IntArray,
    private val bucketDurationMs: Long,
    private var windowStartMs: Long,
    var lastUpdateMs: Long
) {
    companion object {
        // 서브윈도우 개수 (정확도와 성능의 균형점)
        private const val BUCKET_COUNT = 10
        
        /**
         * 초기 상태 생성
         * @param windowMs 전체 윈도우 크기 (밀리초)
         * @param nowMs 현재 시각 (밀리초)
         * @return 초기화된 슬라이딩 윈도우 카운터 상태
         */
        fun init(windowMs: Long, nowMs: Long): SlidingWindowCounterState {
            val bucketDuration = windowMs / BUCKET_COUNT
            return SlidingWindowCounterState(
                buckets = IntArray(BUCKET_COUNT) { 0 },
                bucketDurationMs = bucketDuration,
                windowStartMs = nowMs - windowMs,
                lastUpdateMs = nowMs
            )
        }
    }

    /**
     * 요청에 대한 허용/거부 결정을 수행
     * 
     * 처리 순서:
     * 1. 버킷 배열을 현재 시간에 맞게 업데이트 (슬라이딩)
     * 2. 현재 윈도우 내 요청 수를 가중평균으로 근사 계산
     * 3. 제한 검사 후 허용/거부 결정
     * 4. 허용 시 현재 버킷의 카운트 증가
     * 
     * @param nowMs 현재 시각 (밀리초)
     * @param windowMs 윈도우 크기 (밀리초)  
     * @param limit 최대 허용 요청 수
     * @return 허용/거부 결정과 메타데이터
     */
    fun decide(nowMs: Long, windowMs: Long, limit: Int): Decision {
        // 1. 윈도우를 현재 시간에 맞게 슬라이딩
        slideWindow(nowMs, windowMs)
        
        // 2. 현재 윈도우 내 요청 수 근사 계산
        val currentRequestCount = calculateWeightedCount(nowMs, windowMs)
        
        // 3. 제한 검사 및 처리
        val allowed = currentRequestCount < limit
        if (allowed) {
            // 허용 시: 현재 버킷의 카운트 증가
            incrementCurrentBucket(nowMs, windowMs)
        }
        
        // 4. 상태 업데이트 및 결과 반환
        lastUpdateMs = nowMs
        return createDecision(allowed, limit, currentRequestCount, nowMs, windowMs)
    }

    /**
     * 윈도우를 현재 시간에 맞게 슬라이딩
     * 
     * 슬라이딩 로직:
     * - 새로운 윈도우 시작 시각 계산
     * - 윈도우가 이동한 만큼 버킷들을 시프트하고 초기화
     * - 이동한 버킷의 카운트는 0으로 리셋
     * 
     * @param nowMs 현재 시각
     * @param windowMs 윈도우 크기
     */
    private fun slideWindow(nowMs: Long, windowMs: Long) {
        val newWindowStartMs = nowMs - windowMs
        val timeDeltaMs = newWindowStartMs - windowStartMs
        
        if (timeDeltaMs <= 0) return // 시간이 역행하거나 변화가 없으면 스킵
        
        // 이동해야 할 버킷 수 계산
        val bucketsToSlide = (timeDeltaMs / bucketDurationMs).toInt()
        
        if (bucketsToSlide >= BUCKET_COUNT) {
            // 전체 윈도우보다 오래된 경우: 모든 버킷 초기화
            buckets.fill(0)
        } else if (bucketsToSlide > 0) {
            // 부분 슬라이딩: 배열을 왼쪽으로 시프트하고 새 버킷들을 0으로 초기화
            for (i in 0 until BUCKET_COUNT - bucketsToSlide) {
                buckets[i] = buckets[i + bucketsToSlide]
            }
            for (i in BUCKET_COUNT - bucketsToSlide until BUCKET_COUNT) {
                buckets[i] = 0
            }
        }
        
        windowStartMs = newWindowStartMs
    }

    /**
     * 현재 윈도우 내 요청 수를 가중평균으로 계산
     * 
     * 계산 방식:
     * - 완전히 포함된 버킷: 전체 카운트 사용
     * - 부분적으로 포함된 버킷: 포함 비율에 따라 가중치 적용
     * 
     * 예시: 현재 시각이 버킷 경계 사이에 있을 때
     * - 이전 버킷: 30% 포함 → 카운트 * 0.3
     * - 현재 버킷: 70% 포함 → 카운트 * 0.7
     * 
     * @param nowMs 현재 시각
     * @param windowMs 윈도우 크기
     * @return 가중평균된 요청 수
     */
    private fun calculateWeightedCount(nowMs: Long, windowMs: Long): Double {
        val windowEndMs = nowMs
        var totalCount = 0.0
        
        for (i in buckets.indices) {
            val bucketStartMs = windowStartMs + i * bucketDurationMs
            val bucketEndMs = bucketStartMs + bucketDurationMs
            
            // 이 버킷이 현재 윈도우와 겹치는 부분 계산
            val overlapStart = maxOf(bucketStartMs, windowEndMs - windowMs)
            val overlapEnd = minOf(bucketEndMs, windowEndMs)
            
            if (overlapStart < overlapEnd) {
                // 겹치는 시간 비율 계산
                val overlapRatio = (overlapEnd - overlapStart).toDouble() / bucketDurationMs
                totalCount += buckets[i] * overlapRatio
            }
        }
        
        return totalCount
    }

    /**
     * 현재 시각에 해당하는 버킷의 카운트를 1 증가
     * @param nowMs 현재 시각
     * @param windowMs 윈도우 크기 (사용하지 않지만 일관성을 위해 유지)
     */
    private fun incrementCurrentBucket(nowMs: Long, windowMs: Long) {
        // 현재 시각이 속하는 버킷의 인덱스 계산
        val bucketIndex = ((nowMs - windowStartMs) / bucketDurationMs).toInt()
            .coerceIn(0, BUCKET_COUNT - 1)
        
        buckets[bucketIndex]++
    }

    /**
     * 결정 객체 생성
     * @param allowed 요청 허용 여부
     * @param limit 최대 허용 요청 수
     * @param currentCount 현재 윈도우 내 근사 요청 수
     * @param nowMs 현재 시각
     * @param windowMs 윈도우 크기
     * @return Decision 객체
     */
    private fun createDecision(
        allowed: Boolean,
        limit: Int,
        currentCount: Double,
        nowMs: Long,
        windowMs: Long
    ): Decision {
        // 남은 요청 수 계산 (허용된 경우 1 차감)
        val remaining = if (allowed) {
            (limit - ceil(currentCount + 1)).toInt().coerceAtLeast(0)
        } else {
            (limit - ceil(currentCount)).toInt().coerceAtLeast(0)
        }
        
        // 재시도 대기 시간 계산
        val retryAfter = calculateRetryAfter(allowed, windowMs)
        
        return Decision(allowed, remaining, retryAfter)
    }

    /**
     * 차단된 경우 재시도까지의 대기 시간 계산
     * 
     * 간단한 근사 방식: 다음 버킷까지의 시간
     * 더 정확한 방식을 위해서는 가장 오래된 버킷의 만료 시간 계산 가능
     * 
     * @param allowed 허용 여부
     * @param windowMs 윈도우 크기
     * @return 밀리초 단위 대기 시간
     */
    private fun calculateRetryAfter(allowed: Boolean, windowMs: Long): Long {
        return if (allowed) 0L else bucketDurationMs.coerceAtLeast(1000L)
    }

    /**
     * 현재 모든 버킷의 합계 반환 (디버깅/모니터링용)
     * @return 전체 버킷의 카운트 합계
     */
    fun getTotalCount(): Int = buckets.sum()
    
    /**
     * 버킷 배열의 복사본 반환 (디버깅용)
     * @return 현재 버킷 상태의 복사본
     */
    fun getBuckets(): IntArray = buckets.copyOf()

    // IntArray를 포함한 data class의 equals/hashCode 올바른 구현
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SlidingWindowCounterState

        if (!buckets.contentEquals(other.buckets)) return false
        if (bucketDurationMs != other.bucketDurationMs) return false
        if (windowStartMs != other.windowStartMs) return false
        if (lastUpdateMs != other.lastUpdateMs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = buckets.contentHashCode()
        result = 31 * result + bucketDurationMs.hashCode()
        result = 31 * result + windowStartMs.hashCode()
        result = 31 * result + lastUpdateMs.hashCode()
        return result
    }
}

/**
 * Sliding Window Counter 알고리즘을 구현한 Rate Limiter
 * 
 * 특징:
 * - 중간 정확도: Sliding Window Log보다 효율적, Fixed Window보다 정확
 * - 메모리 효율: 고정된 버킷 배열 사용 (사용자당 ~120 bytes)
 * - 시간 복잡도: O(1) (버킷 수가 고정되어 있어 상수 시간)
 * - 근사 계산: 가중평균을 통한 요청 수 추정
 * 
 * Trade-off:
 * + Fixed Window보다 정확한 제한 (경계 문제 완화)
 * + Sliding Window Log보다 메모리 효율적
 * + 일정한 성능 특성 (O(1))
 * - Sliding Window Log보다 정확도 떨어짐 (근사치)
 * - Token Bucket보다 복잡한 구현
 * 
 * @param clock 시간 제공자 (테스트용 주입 가능)
 * @param states 사용자별 슬라이딩 윈도우 카운터 상태 저장소
 */
class SlidingWindowCounterLimiter(
    private val clock: Clock = Clock.systemUTC(),
    private val states: ConcurrentHashMap<StateKey, SlidingWindowCounterState> = ConcurrentHashMap()
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
     * 3. 동기화된 블록에서 슬라이딩 윈도우 카운터 기반 검사
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
            SlidingWindowCounterState.init(windowMs, nowMs) 
        }

        // 개별 사용자 상태에 대한 동기화
        // 배열 연산과 복합 계산의 원자성을 보장
        return synchronized(state) {
            state.decide(nowMs, windowMs, rule.maxRequest)
        }
    }

    /**
     * 비활성 사용자 상태 정리 (메모리 누수 방지)
     * 
     * 정리 기준: 마지막 업데이트 시점으로부터 일정 시간 경과
     * 버킷 배열은 고정 크기이지만 사용자 수가 계속 증가할 수 있으므로 정리 필요
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
            (nowMs - state.lastUpdateMs) >= idleMillis 
        }
    }

    /**
     * 현재 저장된 전체 사용자 수 반환 (모니터링용)
     * @return 활성 사용자 수
     */
    fun getActiveUserCount(): Int = states.size

    /**
     * 특정 사용자의 현재 버킷 상태 반환 (디버깅용)
     * @param userId 사용자 식별자  
     * @param rule 제한 규칙
     * @return 버킷 상태 배열, 사용자 없으면 빈 배열
     */
    fun getUserBuckets(userId: UserId, rule: RateLimitRule): IntArray {
        val key = StateKey(userId, rule.timeWindowSeconds)
        return states[key]?.getBuckets() ?: intArrayOf()
    }

    /**
     * 특정 사용자의 현재 총 요청 수 반환 (디버깅용)
     * @param userId 사용자 식별자
     * @param rule 제한 규칙
     * @return 현재 모든 버킷의 요청 수 합계
     */
    fun getUserTotalCount(userId: UserId, rule: RateLimitRule): Int {
        val key = StateKey(userId, rule.timeWindowSeconds)
        return states[key]?.getTotalCount() ?: 0
    }
}
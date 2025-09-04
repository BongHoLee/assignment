package pay.assignment.distributed_redis_based

import java.time.Clock
import java.time.Instant

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
 * Redis 연결 설정을 위한 인터페이스
 * 
 * 실제 운영 환경에서는 Spring Data Redis, Lettuce, Jedis 등을 사용
 * 여기서는 데모용으로 간단한 인터페이스 정의
 */
interface RedisClient {
    /**
     * Redis INCR 연산 (카운터 증가)
     * @param key Redis 키
     * @return 증가 후 값
     */
    fun incr(key: String): Long
    
    /**
     * Redis EXPIRE 연산 (TTL 설정)
     * @param key Redis 키
     * @param seconds TTL (초)
     * @return 성공 여부
     */
    fun expire(key: String, seconds: Int): Boolean
    
    /**
     * Redis TTL 조회 (남은 수명 확인)
     * @param key Redis 키
     * @return 남은 TTL (초), 키가 없으면 -2, TTL이 없으면 -1
     */
    fun ttl(key: String): Long
    
    /**
     * Redis GET 연산 (값 조회)
     * @param key Redis 키
     * @return 저장된 값, 없으면 null
     */
    fun get(key: String): String?
    
    /**
     * Redis DEL 연산 (키 삭제)
     * @param key Redis 키
     * @return 삭제된 키 개수
     */
    fun del(key: String): Long
}

/**
 * 메모리 기반 Redis 클라이언트 모의 구현
 * 
 * 실제 프로덕션에서는 Lettuce나 Jedis를 사용
 * 여기서는 Redis의 주요 연산을 인메모리로 시뮬레이션
 * 
 * 주의: 이 구현은 단일 인스턴스 내에서만 동작하며, 실제 분산 환경과는 다름
 */
class InMemoryRedisClient(
    private val clock: Clock = Clock.systemUTC()
) : RedisClient {
    
    // 키-값 저장소 (실제 Redis의 String 타입)
    private val storage = mutableMapOf<String, String>()
    
    // 키별 TTL 저장소 (밀리초 단위 만료 시각)
    private val expiry = mutableMapOf<String, Long>()
    
    /**
     * 만료된 키들을 정리하는 내부 메서드
     * 실제 Redis는 백그라운드에서 자동으로 수행
     */
    private fun cleanupExpiredKeys() {
        val now = clock.millis()
        val expiredKeys = expiry.filter { (_, expiryTime) -> now >= expiryTime }.keys
        expiredKeys.forEach { key ->
            storage.remove(key)
            expiry.remove(key)
        }
    }
    
    /**
     * 키가 만료되었는지 확인
     * @param key 확인할 키
     * @return 만료되었으면 true
     */
    private fun isExpired(key: String): Boolean {
        val expiryTime = expiry[key] ?: return false
        return clock.millis() >= expiryTime
    }
    
    override fun incr(key: String): Long {
        cleanupExpiredKeys()
        
        if (isExpired(key)) {
            storage.remove(key)
            expiry.remove(key)
        }
        
        val currentValue = storage[key]?.toLongOrNull() ?: 0L
        val newValue = currentValue + 1
        storage[key] = newValue.toString()
        return newValue
    }
    
    override fun expire(key: String, seconds: Int): Boolean {
        cleanupExpiredKeys()
        
        return if (storage.containsKey(key) && !isExpired(key)) {
            val expiryTime = clock.millis() + (seconds * 1000L)
            expiry[key] = expiryTime
            true
        } else {
            false
        }
    }
    
    override fun ttl(key: String): Long {
        cleanupExpiredKeys()
        
        return when {
            !storage.containsKey(key) -> -2L  // 키가 존재하지 않음
            !expiry.containsKey(key) -> -1L   // TTL이 설정되지 않음
            else -> {
                val remaining = (expiry[key]!! - clock.millis()) / 1000L
                if (remaining <= 0) -2L else remaining
            }
        }
    }
    
    override fun get(key: String): String? {
        cleanupExpiredKeys()
        
        return if (isExpired(key)) {
            storage.remove(key)
            expiry.remove(key)
            null
        } else {
            storage[key]
        }
    }
    
    override fun del(key: String): Long {
        val exists = storage.containsKey(key)
        storage.remove(key)
        expiry.remove(key)
        return if (exists) 1L else 0L
    }
}

/**
 * Redis 기반 분산 Rate Limiter 구현
 * 
 * 특징:
 * - 분산 환경 지원: 여러 서버 인스턴스 간 상태 공유
 * - 영속성: Redis를 통한 데이터 지속성 보장  
 * - 원자성: Redis 연산의 원자성을 통한 정확한 카운팅
 * - 확장성: 수평 확장 가능한 아키텍처
 * 
 * 알고리즘: Fixed Window (Redis 기반)
 * - 각 시간 윈도우를 Redis 키로 표현
 * - INCR 연산으로 원자적 카운터 증가
 * - EXPIRE로 윈도우 만료 시간 설정
 * 
 * Redis 키 형식:
 * "ratelimit:{userId}:{windowStart}" 
 * 예: "ratelimit:user123:1701234000" (2023-11-29 00:00:00 시작 윈도우)
 * 
 * Trade-offs:
 * + 분산 환경 완벽 지원
 * + 서버 재시작 시에도 상태 유지
 * + Redis의 검증된 성능과 안정성
 * - 네트워크 지연 및 Redis 의존성
 * - 단순한 Fixed Window 알고리즘 (경계 문제)
 * 
 * @param redisClient Redis 클라이언트 (Lettuce, Jedis 등)
 * @param clock 시간 제공자 (테스트용 주입 가능)
 * @param keyPrefix Redis 키 접두사 (네임스페이스 분리)
 */
class RedisRateLimiter(
    private val redisClient: RedisClient = InMemoryRedisClient(),
    private val clock: Clock = Clock.systemUTC(),
    private val keyPrefix: String = "ratelimit"
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
     * Redis 기반 Fixed Window 처리 흐름:
     * 1. 현재 시각을 기준으로 윈도우 시작점 계산
     * 2. Redis 키 생성 (사용자ID + 윈도우시작점)
     * 3. INCR 연산으로 원자적 카운터 증가
     * 4. 첫 요청인 경우 EXPIRE로 TTL 설정
     * 5. 제한 검사 후 결과 반환
     * 
     * @param userId 사용자 식별자
     * @param rule 제한 규칙 (maxRequest/timeWindowSeconds)
     * @param now 검사 기준 시각
     * @return 허용/거부 결정과 메타데이터
     */
    override fun checkAt(userId: UserId, rule: RateLimitRule, now: Instant): Decision {
        val nowMs = now.toEpochMilli()
        val windowMs = rule.timeWindowSeconds * 1000L
        
        // 1. Fixed Window 시작점 계산 (윈도우 경계에 정렬)
        // 예: 5초 윈도우에서 7초 → 5초, 12초 → 10초
        val windowStartMs = (nowMs / windowMs) * windowMs
        val windowStartSeconds = windowStartMs / 1000
        
        // 2. Redis 키 생성 (네임스페이스:사용자ID:윈도우시작점)
        val redisKey = "$keyPrefix:${userId.id}:$windowStartSeconds"
        
        try {
            // 3. 원자적 카운터 증가 (Redis INCR의 핵심 장점)
            // INCR은 키가 없으면 0에서 시작하여 1로 설정
            val currentCount = redisClient.incr(redisKey)
            
            // 4. 첫 요청인 경우 TTL 설정 (메모리 누수 방지)
            if (currentCount == 1L) {
                // 윈도우 크기 + 여유시간으로 TTL 설정
                val ttlSeconds = rule.timeWindowSeconds + 1
                redisClient.expire(redisKey, ttlSeconds)
            }
            
            // 5. 제한 검사 및 결과 생성
            val allowed = currentCount <= rule.maxRequest
            
            return createDecision(
                allowed = allowed,
                currentCount = currentCount.toInt(),
                limit = rule.maxRequest,
                windowStartMs = windowStartMs,
                windowMs = windowMs,
                nowMs = nowMs
            )
            
        } catch (e: Exception) {
            // Redis 연결 실패 시 Fail-Open 정책 (보안보다 가용성 우선)
            // 실제 운영에서는 로그 기록, 모니터링 알람, Fallback 로직 필요
            return Decision(
                allowed = true,  // 연결 실패 시 허용 (가용성 우선)
                remaining = rule.maxRequest,
                retryAfterMillis = 0L
            )
        }
    }

    /**
     * 결정 객체 생성
     * 
     * @param allowed 요청 허용 여부
     * @param currentCount 현재 윈도우 내 요청 수
     * @param limit 최대 허용 요청 수
     * @param windowStartMs 현재 윈도우 시작 시각
     * @param windowMs 윈도우 크기
     * @param nowMs 현재 시각
     * @return Decision 객체
     */
    private fun createDecision(
        allowed: Boolean,
        currentCount: Int,
        limit: Int,
        windowStartMs: Long,
        windowMs: Long,
        nowMs: Long
    ): Decision {
        // 남은 요청 수 계산 (0 이하로 내려가지 않도록)
        val remaining = (limit - currentCount).coerceAtLeast(0)
        
        // 재시도 대기 시간 계산
        val retryAfter = if (allowed) {
            0L  // 허용된 경우 즉시 다음 요청 가능
        } else {
            // 차단된 경우: 다음 윈도우까지 대기
            calculateRetryAfter(windowStartMs, windowMs, nowMs)
        }
        
        return Decision(allowed, remaining, retryAfter)
    }

    /**
     * 차단된 경우 재시도까지의 대기 시간 계산
     * 
     * Fixed Window의 특성상 다음 윈도우 시작까지 대기
     * 
     * 예시: 윈도우 5-10초, 현재 8초, 차단됨 → 2초 후 재시도 가능
     * 
     * @param windowStartMs 현재 윈도우 시작 시각
     * @param windowMs 윈도우 크기
     * @param nowMs 현재 시각
     * @return 밀리초 단위 대기 시간
     */
    private fun calculateRetryAfter(windowStartMs: Long, windowMs: Long, nowMs: Long): Long {
        val nextWindowStartMs = windowStartMs + windowMs
        return (nextWindowStartMs - nowMs).coerceAtLeast(1L)
    }

    /**
     * 특정 사용자의 현재 윈도우 카운트 조회 (모니터링용)
     * 
     * @param userId 사용자 식별자
     * @param rule 제한 규칙
     * @param now 기준 시각 (생략 시 현재 시각)
     * @return 현재 윈도우의 요청 수, 키가 없으면 0
     */
    fun getUserCurrentCount(
        userId: UserId, 
        rule: RateLimitRule, 
        now: Instant = Instant.now(clock)
    ): Int {
        val nowMs = now.toEpochMilli()
        val windowMs = rule.timeWindowSeconds * 1000L
        val windowStartMs = (nowMs / windowMs) * windowMs
        val windowStartSeconds = windowStartMs / 1000
        
        val redisKey = "$keyPrefix:${userId.id}:$windowStartSeconds"
        
        return try {
            redisClient.get(redisKey)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0  // Redis 오류 시 0 반환
        }
    }

    /**
     * 특정 사용자의 현재 윈도우 TTL 조회 (디버깅용)
     * 
     * @param userId 사용자 식별자  
     * @param rule 제한 규칙
     * @param now 기준 시각 (생략 시 현재 시각)
     * @return TTL (초), 키가 없으면 -2, TTL이 없으면 -1
     */
    fun getUserWindowTtl(
        userId: UserId,
        rule: RateLimitRule,
        now: Instant = Instant.now(clock)
    ): Long {
        val nowMs = now.toEpochMilli()
        val windowMs = rule.timeWindowSeconds * 1000L
        val windowStartMs = (nowMs / windowMs) * windowMs
        val windowStartSeconds = windowStartMs / 1000
        
        val redisKey = "$keyPrefix:${userId.id}:$windowStartSeconds"
        
        return try {
            redisClient.ttl(redisKey)
        } catch (e: Exception) {
            -2L  // Redis 오류 시 존재하지 않음으로 처리
        }
    }

    /**
     * 특정 사용자의 Rate Limit 상태 초기화 (관리용)
     * 
     * 주의: 이 메서드는 관리 목적으로만 사용하며, 일반적인 Rate Limiting 로직에서는 사용하지 않음
     * 
     * @param userId 사용자 식별자
     * @param rule 제한 규칙
     * @param now 기준 시각 (생략 시 현재 시각)
     * @return 삭제된 키 개수
     */
    fun resetUserLimit(
        userId: UserId,
        rule: RateLimitRule,
        now: Instant = Instant.now(clock)
    ): Long {
        val nowMs = now.toEpochMilli()
        val windowMs = rule.timeWindowSeconds * 1000L
        val windowStartMs = (nowMs / windowMs) * windowMs
        val windowStartSeconds = windowStartMs / 1000
        
        val redisKey = "$keyPrefix:${userId.id}:$windowStartSeconds"
        
        return try {
            redisClient.del(redisKey)
        } catch (e: Exception) {
            0L  // Redis 오류 시 삭제 실패로 처리
        }
    }
}
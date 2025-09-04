package pay.assignment.ratelimit_v3.aspect

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import pay.assignment.ratelimit_v3.*
import pay.assignment.ratelimit_v3.annotation.SlidingWindowLimited
import javax.servlet.http.HttpServletRequest

/**
 * Sliding Window Rate Limiter AOP 구현
 * 
 * @SlidingWindowLimited 어노테이션이 적용된 메서드에 대해 Sliding Window 기반 Rate Limiting 수행
 * 
 * Sliding Window 장점:
 * - 정확한 시간 윈도우: Fixed Window의 경계 효과 없음
 * - 부드러운 제어: 시간에 따른 자연스러운 요청 허용
 * - 정밀한 제어: 정확히 지정된 시간 범위 내에서만 요청 카운트
 * 
 * 단점:
 * - 높은 메모리 사용량: 각 요청의 타임스탬프 저장
 * - 복잡한 구현: Sorted Set을 이용한 시간 기반 관리
 */
@Aspect
@Component
class SlidingWindowAspect {

    @Autowired
    private lateinit var slidingWindowRateLimiter: SlidingWindowRateLimiter

    /**
     * @SlidingWindowLimited 어노테이션 처리
     * 
     * Sliding Window 동작:
     * 1. 현재 시간 기준으로 윈도우 범위 계산
     * 2. 윈도우 밖의 오래된 요청들 제거
     * 3. 윈도우 내 요청 수 카운트
     * 4. 제한 이내인 경우 새 요청 추가
     */
    @Around("@annotation(slidingWindowLimited)")
    fun slidingWindowLimit(joinPoint: ProceedingJoinPoint, slidingWindowLimited: SlidingWindowLimited): Any? {
        // 1. 사용자 ID 추출
        val userId = extractUserIdFromRequest()
        
        // 2. Sliding Window 규칙 생성
        val rule = SlidingWindowRule(
            maxRequests = slidingWindowLimited.maxRequests,
            windowSeconds = slidingWindowLimited.windowSeconds
        )
        
        // 3. Sliding Window 확인
        val decision = slidingWindowRateLimiter.check(userId, rule)
        
        // 4. 요청이 제한된 경우 HTTP 429 응답
        if (!decision.allowed) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                createSlidingWindowErrorMessage(decision)
            )
        }
        
        // 5. 요청이 허용된 경우 원본 메서드 실행
        return joinPoint.proceed()
    }

    /**
     * HTTP 요청에서 사용자 ID 추출
     * Sliding Window는 사용자별로 독립적인 시간 윈도우 관리
     */
    private fun extractUserIdFromRequest(): String {
        val request = getCurrentHttpRequest()
            ?: return "anonymous"
        
        // X-User-Id 헤더에서 우선 추출
        request.getHeader("X-User-Id")?.let { userId ->
            if (userId.isNotBlank()) {
                return userId.trim()
            }
        }
        
        // User-Id 헤더에서 추출
        request.getHeader("User-Id")?.let { userId ->
            if (userId.isNotBlank()) {
                return userId.trim()
            }
        }
        
        // Authorization 헤더에서 추출
        request.getHeader("Authorization")?.let { auth ->
            if (auth.startsWith("Bearer ", ignoreCase = true)) {
                val token = auth.substring(7).trim()
                if (token.isNotBlank()) {
                    return "user_${token.hashCode().toString().takeLast(8)}"
                }
            }
        }
        
        // IP 기반 식별
        return "ip_${extractClientIp(request)}"
    }

    /**
     * 현재 HTTP 요청 객체 조회
     */
    private fun getCurrentHttpRequest(): HttpServletRequest? {
        return try {
            val attributes = RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes
            attributes.request
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 클라이언트 IP 추출
     */
    private fun extractClientIp(request: HttpServletRequest): String {
        val xForwardedFor = request.getHeader("X-Forwarded-For")
        if (!xForwardedFor.isNullOrBlank()) {
            return xForwardedFor.split(",")[0].trim()
        }
        
        val xRealIp = request.getHeader("X-Real-IP")
        if (!xRealIp.isNullOrBlank()) {
            return xRealIp.trim()
        }
        
        return request.remoteAddr ?: "unknown"
    }

    /**
     * Sliding Window 제한 시 에러 메시지 생성
     * 
     * 사용자에게 정확한 윈도우 정보와 재시도 시간 제공
     */
    private fun createSlidingWindowErrorMessage(decision: SlidingWindowDecision): String {
        val retryAfterSeconds = (decision.retryAfterMs / 1000.0).let { 
            if (it < 1) "1" else "%.1f".format(it) 
        }
        
        val windowInfo = "${decision.maxRequests} requests per ${decision.windowSeconds} seconds"
        val currentInfo = "${decision.currentCount}/${decision.maxRequests} requests used"
        
        val oldestRequestInfo = decision.oldestRequestTime?.let { oldest ->
            val ageSeconds = java.time.Duration.between(oldest, java.time.Instant.now()).seconds
            " Oldest request: ${ageSeconds}s ago."
        } ?: ""
        
        return "Sliding window rate limit exceeded: $windowInfo. " +
                "Current usage: $currentInfo.$oldestRequestInfo " +
                "Retry after $retryAfterSeconds seconds."
    }
}
package pay.assignment.sliding_window_log.aspect

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import pay.assignment.sliding_window_log.annotation.RateLimited
import pay.assignment.sliding_window_log.*

/**
 * Rate Limiting을 위한 AOP Aspect
 * 
 * @RateLimited 어노테이션이 붙은 메서드에 대해 자동으로 Rate Limiting을 적용
 * Sliding Window Log 알고리즘을 사용하여 사용자별 요청 제한 수행
 */
@Aspect
@Component
class RateLimiterAspect {

    // Sliding Window Log 기반 Rate Limiter 인스턴스
    // 싱글톤으로 관리되어 모든 요청에 대해 공유 상태 유지
    private val rateLimiter: RateLimiter = SlidingWindowLogLimiter()

    /**
     * @RateLimited 어노테이션이 붙은 메서드 실행 전후에 Rate Limiting 로직 적용
     * 
     * 처리 흐름:
     * 1. HTTP 요청에서 사용자 식별자 추출
     * 2. 어노테이션 설정으로부터 제한 규칙 생성  
     * 3. Rate Limiter를 통한 허용/거부 결정
     * 4. 허용 시 원본 메서드 실행, 거부 시 429 응답 반환
     * 
     * @param joinPoint AOP 조인 포인트 (실제 메서드 실행 제어)
     * @param rateLimited 어노테이션 인스턴스 (설정 값 추출용)
     * @return 원본 메서드 결과 또는 429 에러 응답
     */
    @Around("@annotation(rateLimited)")
    fun rateLimit(joinPoint: ProceedingJoinPoint, rateLimited: RateLimited): Any? {
        // 1. 사용자 식별자 추출 (Header → Session → IP 순)
        val userId = extractUserId()
        
        // 2. 어노테이션 설정을 기반으로 제한 규칙 생성
        val rule = RateLimitRule(rateLimited.maxRequest, rateLimited.timeWindowSeconds)
        
        // 3. Sliding Window Log을 통한 허용/거부 결정
        val decision = rateLimiter.check(userId, rule)
        
        // 4. 결정에 따른 처리
        return if (decision.allowed) {
            // 허용 시: 원본 메서드 실행
            joinPoint.proceed()
        } else {
            // 거부 시: 429 Too Many Requests 응답 생성
            createRateLimitExceededResponse(decision)
        }
    }

    /**
     * HTTP 요청으로부터 사용자 식별자 추출
     * 
     * 추출 우선순위:
     * 1. X-User-ID 헤더 (명시적 사용자 식별)
     * 2. HTTP 세션 ID (세션 기반 식별)  
     * 3. 클라이언트 IP 주소 (IP 기반 제한)
     * 4. "anonymous" (기본값)
     * 
     * @return 사용자 식별자를 포함한 UserId 객체
     */
    private fun extractUserId(): UserId {
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
        
        // 우선순위에 따른 사용자 식별자 추출
        val userIdFromHeader = request.getHeader("X-User-ID")  // 1순위: 헤더
        val userIdFromSession = request.session?.id             // 2순위: 세션
        val userIdFromRemoteAddr = request.remoteAddr           // 3순위: IP
        
        // null 병합 연산자를 통한 fallback 처리
        val userId = userIdFromHeader ?: userIdFromSession ?: userIdFromRemoteAddr ?: "anonymous"
        return UserId(userId)
    }

    /**
     * Rate Limit 초과 시 HTTP 응답 생성
     * 
     * HTTP 상태코드: 429 Too Many Requests
     * 응답 헤더:
     * - X-RateLimit-Remaining: 남은 요청 수 (토큰 수)
     * - Retry-After: 재시도까지 대기 시간 (초 단위)
     * 
     * @param decision Rate Limit 검사 결과
     * @return 429 상태코드와 에러 메시지를 포함한 ResponseEntity
     */
    private fun createRateLimitExceededResponse(decision: Decision): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("X-RateLimit-Remaining", decision.remaining.toString())
            .header("Retry-After", (decision.retryAfterMillis / 1000).toString())
            .body(mapOf(
                "error" to "Too Many Requests",
                "message" to "Rate limit exceeded. Try again later.",
                "retryAfterSeconds" to (decision.retryAfterMillis / 1000)
            ))
    }
}
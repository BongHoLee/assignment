package pay.assignment.ratelimit_v1.aspect

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import pay.assignment.ratelimit_v1.*
import pay.assignment.ratelimit_v1.annotation.RateLimited
import javax.servlet.http.HttpServletRequest

/**
 * Rate Limiter AOP 구현
 * 
 * @RateLimited 어노테이션이 적용된 메서드에 대해 Rate Limiting을 수행
 * 
 * 동작 방식:
 * 1. HTTP 요청에서 사용자 ID 추출 (X-User-Id 헤더)
 * 2. 어노테이션에서 Rate Limit 규칙 추출
 * 3. RedisRateLimiter를 통해 요청 허용 여부 판단
 * 4. 허용되지 않는 경우 HTTP 429 응답 반환
 */
@Aspect
@Component
class RateLimiterAspect {

    @Autowired
    private lateinit var rateLimiter: RateLimiter

    /**
     * @RateLimited 어노테이션이 적용된 메서드 실행 전/후 처리
     * 
     * 사용자별 요청 제한을 확인하고, 제한을 초과한 경우 예외 발생
     * 
     * @param joinPoint AOP 조인 포인트
     * @param rateLimited Rate Limit 설정 어노테이션
     * @return 원본 메서드의 실행 결과 (허용된 경우)
     * @throws ResponseStatusException 요청이 제한된 경우 HTTP 429 예외
     */
    @Around("@annotation(rateLimited)")
    fun rateLimit(joinPoint: ProceedingJoinPoint, rateLimited: RateLimited): Any? {
        // 1. 현재 HTTP 요청에서 사용자 ID 추출
        val userId = extractUserIdFromRequest()
        
        // 2. Rate Limit 규칙 생성
        val rule = RateLimitRule(
            maxRequest = rateLimited.maxRequest,
            timeWindowSeconds = rateLimited.timeWindowSeconds
        )
        
        // 3. Rate Limiter를 통해 요청 허용 여부 확인
        val decision = rateLimiter.check(userId, rule)
        
        // 4. 요청이 제한된 경우 HTTP 429 응답
        if (!decision.allowed) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                createRateLimitErrorMessage(decision, rule)
            )
        }
        
        // 5. 요청이 허용된 경우 원본 메서드 실행
        return joinPoint.proceed()
    }

    /**
     * HTTP 요청에서 사용자 ID 추출
     * 
     * 추출 우선순위:
     * 1. X-User-Id 헤더
     * 2. User-Id 헤더 
     * 3. Authorization 헤더에서 추출 (Bearer 토큰의 경우)
     * 4. 기본값: "anonymous" (인증되지 않은 사용자)
     * 
     * @return 사용자 ID
     */
    private fun extractUserIdFromRequest(): UserId {
        val request = getCurrentHttpRequest()
            ?: return UserId("anonymous")
        
        // X-User-Id 헤더에서 우선 추출
        request.getHeader("X-User-Id")?.let { userId ->
            if (userId.isNotBlank()) {
                return UserId(userId.trim())
            }
        }
        
        // User-Id 헤더에서 추출
        request.getHeader("User-Id")?.let { userId ->
            if (userId.isNotBlank()) {
                return UserId(userId.trim())
            }
        }
        
        // Authorization 헤더에서 추출 (간단한 Bearer 토큰 파싱)
        request.getHeader("Authorization")?.let { auth ->
            if (auth.startsWith("Bearer ", ignoreCase = true)) {
                val token = auth.substring(7).trim()
                // 실제 구현에서는 JWT 파싱이나 토큰 검증 로직이 필요
                // 여기서는 토큰 자체를 사용자 ID로 사용 (예시용)
                if (token.isNotBlank()) {
                    return UserId("user_${token.hashCode().toString().takeLast(8)}")
                }
            }
        }
        
        // 클라이언트 IP를 기본 식별자로 사용
        val clientIp = extractClientIp(request)
        return UserId("ip_$clientIp")
    }

    /**
     * 현재 HTTP 요청 객체 조회
     */
    private fun getCurrentHttpRequest(): HttpServletRequest? {
        return try {
            val attributes = RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes
            attributes.request
        } catch (e: Exception) {
            // 비웹 환경이거나 요청 컨텍스트가 없는 경우
            null
        }
    }

    /**
     * 클라이언트 실제 IP 추출 (프록시 고려)
     * 
     * @param request HTTP 요청
     * @return 클라이언트 IP 주소
     */
    private fun extractClientIp(request: HttpServletRequest): String {
        // 프록시를 통한 요청의 경우 실제 클라이언트 IP 확인
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
     * Rate Limit 초과 시 에러 메시지 생성
     * 
     * @param decision Rate Limiter 판단 결과
     * @param rule 적용된 Rate Limit 규칙
     * @return 사용자에게 반환할 에러 메시지
     */
    private fun createRateLimitErrorMessage(decision: Decision, rule: RateLimitRule): String {
        val retryAfterSeconds = (decision.retryAfterMillis / 1000.0).let { 
            if (it < 1) "1" else "%.1f".format(it) 
        }
        
        return "Rate limit exceeded: ${rule.maxRequest} requests per ${rule.timeWindowSeconds} seconds. " +
                "Retry after $retryAfterSeconds seconds."
    }
}
package pay.assignment.ratelimit_v2.aspect

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import pay.assignment.ratelimit_v2.*
import pay.assignment.ratelimit_v2.annotation.TokenBucketLimited
import javax.servlet.http.HttpServletRequest

/**
 * Token Bucket Rate Limiter AOP 구현
 * 
 * @TokenBucketLimited 어노테이션이 적용된 메서드에 대해 Token Bucket 기반 Rate Limiting 수행
 * 
 * Token Bucket 장점:
 * - 버스트 트래픽 허용: 순간적으로 많은 요청 처리 가능
 * - 유연한 제어: 평균 처리율과 버스트 용량을 독립적으로 설정
 * - 공정성: 시간이 지나면서 토큰이 축적되어 일시적 대기 후 요청 가능
 */
@Aspect
@Component
class TokenBucketAspect {

    @Autowired
    private lateinit var tokenBucketRateLimiter: TokenBucketRateLimiter

    /**
     * @TokenBucketLimited 어노테이션 처리
     * 
     * Token Bucket 알고리즘 동작:
     * 1. 사용자별 토큰 버킷 확인
     * 2. 시간 경과에 따른 토큰 자동 보충
     * 3. 요청에 필요한 토큰 차감 시도
     * 4. 토큰 부족 시 다음 보충 시간 계산하여 재시도 정보 제공
     */
    @Around("@annotation(tokenBucketLimited)")
    fun tokenBucketLimit(joinPoint: ProceedingJoinPoint, tokenBucketLimited: TokenBucketLimited): Any? {
        // 1. 사용자 ID 추출
        val userId = extractUserIdFromRequest()
        
        // 2. Token Bucket 규칙 생성
        val rule = TokenBucketRule(
            capacity = tokenBucketLimited.capacity,
            refillTokens = tokenBucketLimited.refillTokens,
            refillPeriodSeconds = tokenBucketLimited.refillPeriodSeconds
        )
        
        // 3. Token Bucket 확인
        val decision = tokenBucketRateLimiter.check(
            userId = userId,
            rule = rule,
            requestTokens = tokenBucketLimited.tokensPerRequest
        )
        
        // 4. 토큰 부족 시 HTTP 429 응답
        if (!decision.allowed) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                createTokenBucketErrorMessage(decision, rule, tokenBucketLimited.tokensPerRequest)
            )
        }
        
        // 5. 토큰이 충분한 경우 원본 메서드 실행
        return joinPoint.proceed()
    }

    /**
     * HTTP 요청에서 사용자 ID 추출
     * Token Bucket은 사용자별로 독립적인 버킷을 유지
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
     * Token Bucket 제한 시 에러 메시지 생성
     * 
     * 사용자에게 Token Bucket 상태와 재시도 정보 제공
     */
    private fun createTokenBucketErrorMessage(
        decision: TokenBucketDecision,
        rule: TokenBucketRule,
        tokensPerRequest: Int
    ): String {
        val retryAfterSeconds = (decision.retryAfterMs / 1000.0).let { 
            if (it < 1) "1" else "%.1f".format(it) 
        }
        
        return "Token bucket exhausted: ${decision.currentTokens}/${decision.capacity} tokens available. " +
                "Bucket refills ${rule.refillTokens} tokens every ${rule.refillPeriodSeconds}s. " +
                "Request needs $tokensPerRequest tokens. Retry after $retryAfterSeconds seconds."
    }
}
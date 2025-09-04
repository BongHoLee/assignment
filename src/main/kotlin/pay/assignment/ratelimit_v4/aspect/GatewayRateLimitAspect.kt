package pay.assignment.ratelimit_v4.aspect

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import pay.assignment.ratelimit_v4.*
import pay.assignment.ratelimit_v4.annotation.GatewayRateLimited
import reactor.core.publisher.Mono
import javax.servlet.http.HttpServletRequest

/**
 * Spring Cloud Gateway 스타일 Rate Limiter AOP 구현
 * 
 * @GatewayRateLimited 어노테이션이 적용된 메서드에 대해 
 * Spring Cloud Gateway의 RedisRateLimiter와 동일한 방식으로 Rate Limiting 수행
 * 
 * Spring Cloud Gateway의 특징:
 * - 검증된 Token Bucket 구현
 * - replenishRate와 burstCapacity 분리로 세밀한 제어
 * - 프로덕션 환경에서 검증된 안정성
 * 
 * 주의사항:
 * - 이 구현은 Reactive가 아닌 동기 방식으로 동작
 * - 실제 Gateway 환경에서는 Reactive WebFlux 사용 권장
 */
@Aspect
@Component
class GatewayRateLimitAspect {

    @Autowired
    private lateinit var springCloudGatewayRateLimiter: SpringCloudGatewayRateLimiter

    /**
     * @GatewayRateLimited 어노테이션 처리
     * 
     * Spring Cloud Gateway 방식 동작:
     * 1. Token Bucket에서 현재 토큰 수 확인
     * 2. 시간 경과에 따른 토큰 보충 (replenishRate)
     * 3. burstCapacity 이내에서 토큰 제한
     * 4. requestedTokens만큼 토큰 소모
     */
    @Around("@annotation(gatewayRateLimited)")
    fun gatewayRateLimit(joinPoint: ProceedingJoinPoint, gatewayRateLimited: GatewayRateLimited): Any? {
        // 1. 사용자 ID 추출
        val userId = extractUserIdFromRequest()
        
        // 2. Gateway Rate Limiter 설정 생성
        val config = GatewayRateLimitConfig(
            replenishRate = gatewayRateLimited.replenishRate,
            burstCapacity = gatewayRateLimited.burstCapacity,
            requestedTokens = gatewayRateLimited.requestedTokens
        )
        
        // 3. Rate Limiting 확인 (Reactive를 동기로 변환)
        val result = springCloudGatewayRateLimiter.isAllowed(userId, config)
            .block() // Reactive를 동기로 변환 (실제 환경에서는 비추천)
            ?: throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Rate limiting check failed"
            )
        
        // 4. 요청이 제한된 경우 HTTP 429 응답
        if (!result.allowed) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                createGatewayErrorMessage(result, config)
            )
        }
        
        // 5. 요청이 허용된 경우 원본 메서드 실행
        return joinPoint.proceed()
    }

    /**
     * HTTP 요청에서 사용자 ID 추출
     * Spring Cloud Gateway와 동일한 방식으로 사용자 식별
     */
    private fun extractUserIdFromRequest(): String {
        val request = getCurrentHttpRequest()
            ?: return "anonymous"
        
        // Spring Cloud Gateway에서 일반적으로 사용하는 식별 방법
        
        // 1. X-User-Id 헤더 (커스텀 헤더)
        request.getHeader("X-User-Id")?.let { userId ->
            if (userId.isNotBlank()) {
                return userId.trim()
            }
        }
        
        // 2. Authorization 헤더에서 JWT 파싱 (간소화된 버전)
        request.getHeader("Authorization")?.let { auth ->
            if (auth.startsWith("Bearer ", ignoreCase = true)) {
                val token = auth.substring(7).trim()
                if (token.isNotBlank()) {
                    // 실제 환경에서는 JWT 파싱하여 사용자 ID 추출
                    return "user_${token.hashCode().toString().takeLast(8)}"
                }
            }
        }
        
        // 3. API Key 헤더
        request.getHeader("X-API-Key")?.let { apiKey ->
            if (apiKey.isNotBlank()) {
                return "api_${apiKey.hashCode().toString().takeLast(8)}"
            }
        }
        
        // 4. IP 기반 식별 (최후 수단)
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
     * Gateway 환경에서 자주 사용되는 헤더들 고려
     */
    private fun extractClientIp(request: HttpServletRequest): String {
        // Gateway를 통해 오는 경우 자주 사용되는 헤더들
        val headers = listOf(
            "X-Forwarded-For",
            "X-Real-IP", 
            "X-Original-Forwarded-For",
            "CF-Connecting-IP" // Cloudflare
        )
        
        for (header in headers) {
            val ip = request.getHeader(header)
            if (!ip.isNullOrBlank() && !"unknown".equals(ip, ignoreCase = true)) {
                return ip.split(",")[0].trim()
            }
        }
        
        return request.remoteAddr ?: "unknown"
    }

    /**
     * Gateway Rate Limit 초과 시 에러 메시지 생성
     * 
     * Spring Cloud Gateway 스타일의 에러 메시지
     */
    private fun createGatewayErrorMessage(
        result: GatewayRateLimitResult,
        config: GatewayRateLimitConfig
    ): String {
        val retryAfterInfo = result.retryAfter?.let { 
            " Retry after ${it.epochSecond} seconds."
        } ?: ""
        
        return "Rate limit exceeded: ${config.replenishRate} requests per second " +
                "(burst capacity: ${config.burstCapacity}, tokens per request: ${config.requestedTokens}). " +
                "Remaining tokens: ${result.tokensRemaining}.$retryAfterInfo"
    }
}
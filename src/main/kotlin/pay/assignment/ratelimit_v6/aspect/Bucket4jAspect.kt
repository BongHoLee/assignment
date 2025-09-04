package pay.assignment.ratelimit_v6.aspect

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import pay.assignment.ratelimit_v6.*
import pay.assignment.ratelimit_v6.annotation.Bucket4jLimited
import java.time.Duration
import javax.servlet.http.HttpServletRequest

/**
 * Bucket4j Rate Limiter AOP 구현
 * 
 * @Bucket4jLimited 어노테이션이 적용된 메서드에 대해 
 * Bucket4j의 완성도 높은 Token Bucket 구현을 활용한 Rate Limiting 수행
 * 
 * Bucket4j의 장점:
 * - Java 생태계의 표준 Rate Limiting 라이브러리
 * - 완벽한 Token Bucket 구현
 * - 다양한 분산 백엔드 지원 (Redis, Hazelcast, Ignite)
 * - 동기/비동기 API 모두 제공
 * - Spring Boot와의 완벽한 통합
 */
@Aspect
@Component
class Bucket4jAspect {

    @Autowired
    private lateinit var bucket4jRateLimiter: Bucket4jRateLimiter

    /**
     * @Bucket4jLimited 어노테이션 처리
     * 
     * Bucket4j Token Bucket 동작:
     * 1. 사용자별 분산 토큰 버킷 생성/조회
     * 2. 설정된 대역폭에 따른 토큰 보충
     * 3. 요청된 토큰 수만큼 소모 시도
     * 4. 결과에 따른 허용/거부 처리
     */
    @Around("@annotation(bucket4jLimited)")
    fun bucket4jLimit(joinPoint: ProceedingJoinPoint, bucket4jLimited: Bucket4jLimited): Any? {
        // 1. 사용자 ID 추출
        val userId = extractUserIdFromRequest()
        
        // 2. Bucket4j 설정 생성
        val config = createBucket4jConfig(bucket4jLimited)
        
        // 3. Rate Limiting 확인
        val result = bucket4jRateLimiter.checkRateLimit(userId, config)
        
        // 4. 요청이 제한된 경우 HTTP 429 응답
        if (!result.allowed) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                createBucket4jErrorMessage(result)
            )
        }
        
        // 5. 요청이 허용된 경우 원본 메서드 실행
        return joinPoint.proceed()
    }

    /**
     * 어노테이션 설정을 Bucket4jConfig로 변환
     */
    private fun createBucket4jConfig(annotation: Bucket4jLimited): Bucket4jConfig {
        val period = Duration.of(annotation.period, annotation.timeUnit.toChronoUnit())
        
        val refillTokens = if (annotation.refillTokens == -1L) {
            annotation.capacity // SIMPLE 타입이거나 명시하지 않은 경우
        } else {
            annotation.refillTokens
        }
        
        val bandwidthConfig = Bucket4jBandwidthConfig(
            type = annotation.type,
            capacity = annotation.capacity,
            refillTokens = refillTokens,
            period = period
        )
        
        return Bucket4jConfig(
            bandwidths = listOf(bandwidthConfig),
            tokensToConsume = annotation.tokensToConsume
        )
    }

    /**
     * HTTP 요청에서 사용자 ID 추출
     * Bucket4j는 분산 환경에서 사용되므로 정확한 사용자 식별이 중요
     */
    private fun extractUserIdFromRequest(): String {
        val request = getCurrentHttpRequest()
            ?: return "anonymous"
        
        // 1. X-User-Id 헤더 (명시적 사용자 식별)
        request.getHeader("X-User-Id")?.let { userId ->
            if (userId.isNotBlank()) {
                return userId.trim()
            }
        }
        
        // 2. Authorization 헤더에서 JWT 파싱
        request.getHeader("Authorization")?.let { auth ->
            if (auth.startsWith("Bearer ", ignoreCase = true)) {
                val token = auth.substring(7).trim()
                if (token.isNotBlank()) {
                    // 실제 환경에서는 JWT 디코딩하여 사용자 ID 추출
                    return "jwt_${token.hashCode().toString().takeLast(10)}"
                }
            }
        }
        
        // 3. API Key 기반 식별
        request.getHeader("X-API-Key")?.let { apiKey ->
            if (apiKey.isNotBlank()) {
                return "api_${apiKey.hashCode().toString().takeLast(10)}"
            }
        }
        
        // 4. Session 기반 식별
        request.getSession(false)?.id?.let { sessionId ->
            return "session_${sessionId.takeLast(10)}"
        }
        
        // 5. IP 기반 식별 (최후 수단)
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
        val headers = listOf(
            "X-Forwarded-For",
            "X-Real-IP",
            "X-Original-Forwarded-For",
            "CF-Connecting-IP", // Cloudflare
            "X-Client-IP",
            "True-Client-IP"
        )
        
        for (header in headers) {
            val ip = request.getHeader(header)
            if (!ip.isNullOrBlank() && 
                !"unknown".equals(ip, ignoreCase = true) &&
                !"127.0.0.1" != ip) {
                return ip.split(",")[0].trim()
            }
        }
        
        return request.remoteAddr ?: "unknown"
    }

    /**
     * Bucket4j Rate Limit 초과 시 에러 메시지 생성
     * 
     * 상세한 버킷 상태 정보 제공
     */
    private fun createBucket4jErrorMessage(result: Bucket4jResult): String {
        val config = result.config
        val bandwidth = config.bandwidths[0] // 현재는 단일 대역폭만 지원
        
        val bandwidthInfo = when (bandwidth.type) {
            Bucket4jBandwidthType.SIMPLE -> {
                "${bandwidth.capacity} tokens per ${formatDuration(bandwidth.period)}"
            }
            Bucket4jBandwidthType.CLASSIC -> {
                "${bandwidth.refillTokens} tokens every ${formatDuration(bandwidth.period)} (capacity: ${bandwidth.capacity})"
            }
        }
        
        val retryAfterSeconds = (result.retryAfterMs / 1000.0).let {
            if (it < 1) "1" else "%.2f".format(it)
        }
        
        return "Bucket4j rate limit exceeded: $bandwidthInfo. " +
                "Remaining tokens: ${result.remainingTokens}. " +
                "Tokens per request: ${config.tokensToConsume}. " +
                "Retry after $retryAfterSeconds seconds. " +
                "Processing time: ${result.processingTimeMs}ms."
    }
    
    /**
     * Duration을 읽기 쉬운 문자열로 변환
     */
    private fun formatDuration(duration: Duration): String {
        return when {
            duration.seconds < 60 -> "${duration.seconds}s"
            duration.toMinutes() < 60 -> "${duration.toMinutes()}m"
            duration.toHours() < 24 -> "${duration.toHours()}h"
            else -> "${duration.toDays()}d"
        }
    }
}
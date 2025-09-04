package pay.assignment.ratelimit_v5.aspect

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import pay.assignment.ratelimit_v5.*
import pay.assignment.ratelimit_v5.annotation.RedissonRateLimited
import java.time.Duration
import javax.servlet.http.HttpServletRequest

/**
 * Redisson RRateLimiter AOP 구현
 * 
 * @RedissonRateLimited 어노테이션이 적용된 메서드에 대해 
 * Redisson의 분산 Rate Limiter 기능을 활용한 제한 수행
 * 
 * Redisson의 장점:
 * - 검증된 분산 동기화: Redis 클러스터 환경에서 완벽한 동기화
 * - 다양한 알고리즘: Token Bucket, Leaky Bucket 등 내장
 * - 고성능: 최적화된 Redis 연산과 Lua 스크립트
 * - 자동 관리: TTL, 클린업 등 자동 처리
 */
@Aspect
@Component
class RedissonRateLimitAspect {

    @Autowired
    private lateinit var redissonRateLimiterWrapper: RedissonRateLimiterWrapper

    /**
     * @RedissonRateLimited 어노테이션 처리
     * 
     * Redisson RRateLimiter 동작:
     * 1. 사용자별 Rate Limiter 인스턴스 생성/조회
     * 2. 설정된 모드에 따라 토큰/허가 획득 시도
     * 3. 성공/실패에 따른 응답 처리
     */
    @Around("@annotation(redissonRateLimited)")
    fun redissonRateLimit(joinPoint: ProceedingJoinPoint, redissonRateLimited: RedissonRateLimited): Any? {
        // 1. 사용자 ID 추출
        val userId = extractUserIdFromRequest()
        
        // 2. Redisson Rate Limiter 설정 생성
        val config = RedissonRateLimitConfig(
            rateType = redissonRateLimited.rateType,
            rate = redissonRateLimited.rate,
            rateInterval = redissonRateLimited.rateInterval,
            rateIntervalUnit = redissonRateLimited.rateIntervalUnit,
            permits = redissonRateLimited.permits,
            mode = redissonRateLimited.mode,
            maxWaitTime = Duration.ofSeconds(redissonRateLimited.maxWaitTimeSeconds)
        )
        
        // 3. Rate Limiting 확인
        val result = redissonRateLimiterWrapper.checkRateLimit(userId, config)
        
        // 4. 요청이 제한된 경우 HTTP 응답
        if (!result.allowed) {
            val status = when (config.mode) {
                RedissonRateLimitMode.IMMEDIATE -> HttpStatus.TOO_MANY_REQUESTS
                RedissonRateLimitMode.BLOCKING -> {
                    // BLOCKING 모드에서 실패는 타임아웃을 의미
                    HttpStatus.REQUEST_TIMEOUT
                }
                RedissonRateLimitMode.FORCE_ACQUIRE -> {
                    // 이론적으로 실패할 수 없지만, 안전을 위해
                    HttpStatus.TOO_MANY_REQUESTS
                }
            }
            
            throw ResponseStatusException(
                status,
                createRedissonErrorMessage(result)
            )
        }
        
        // 5. 요청이 허용된 경우 원본 메서드 실행
        return joinPoint.proceed()
    }

    /**
     * HTTP 요청에서 사용자 ID 추출
     * Redisson은 분산 환경에서 사용되므로 정확한 사용자 식별이 중요
     */
    private fun extractUserIdFromRequest(): String {
        val request = getCurrentHttpRequest()
            ?: return "anonymous"
        
        // Redisson 환경에서 일반적인 식별 방법들
        
        // 1. X-User-Id 헤더 (가장 명확한 식별)
        request.getHeader("X-User-Id")?.let { userId ->
            if (userId.isNotBlank()) {
                return userId.trim()
            }
        }
        
        // 2. JWT 토큰에서 사용자 ID 추출
        request.getHeader("Authorization")?.let { auth ->
            if (auth.startsWith("Bearer ", ignoreCase = true)) {
                val token = auth.substring(7).trim()
                if (token.isNotBlank()) {
                    // 실제 환경에서는 JWT 파싱하여 사용자 ID 추출
                    // 여기서는 토큰 해시를 사용자 ID로 사용
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
        
        // 4. Session ID 기반 식별
        request.getSession(false)?.id?.let { sessionId ->
            return "session_${sessionId.takeLast(10)}"
        }
        
        // 5. 클라이언트 IP 기반 식별 (최후 수단)
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
     * 클라이언트 IP 추출 (분산 환경 고려)
     */
    private fun extractClientIp(request: HttpServletRequest): String {
        // 분산 환경에서 자주 사용되는 헤더들
        val headers = listOf(
            "X-Forwarded-For",
            "X-Real-IP",
            "X-Original-Forwarded-For",
            "CF-Connecting-IP", // Cloudflare
            "X-Client-IP",
            "X-Forwarded",
            "X-Cluster-Client-IP"
        )
        
        for (header in headers) {
            val ip = request.getHeader(header)
            if (!ip.isNullOrBlank() && 
                !"unknown".equals(ip, ignoreCase = true) &&
                !"127.0.0.1" != ip &&
                !"0:0:0:0:0:0:0:1" != ip) {
                return ip.split(",")[0].trim()
            }
        }
        
        return request.remoteAddr ?: "unknown"
    }

    /**
     * Redisson Rate Limit 초과 시 에러 메시지 생성
     * 
     * 모드별로 다른 메시지 제공
     */
    private fun createRedissonErrorMessage(result: RedissonRateLimitResult): String {
        val config = result.rateConfig
        val rateInfo = "${config.rate} ${config.rateIntervalUnit.name.lowercase()} per ${config.rateInterval} ${config.rateIntervalUnit.name.lowercase()}"
        
        val baseMessage = "Redisson rate limit exceeded: $rateInfo"
        val permitInfo = if (config.permits > 1) " (${config.permits} permits per request)" else ""
        val availableInfo = " Available permits: ${result.availablePermits}"
        
        val modeSpecificMessage = when (config.mode) {
            RedissonRateLimitMode.IMMEDIATE -> {
                " Request rejected immediately."
            }
            RedissonRateLimitMode.BLOCKING -> {
                val waitSeconds = result.waitTimeMs / 1000.0
                " Request timed out after waiting ${String.format("%.2f", waitSeconds)} seconds."
            }
            RedissonRateLimitMode.FORCE_ACQUIRE -> {
                " Unexpected failure in force acquire mode."
            }
        }
        
        return baseMessage + permitInfo + availableInfo + modeSpecificMessage
    }
}
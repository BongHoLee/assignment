package pay.assignment.ratelimit_v4.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pay.assignment.ratelimit_v4.annotation.GatewayRateLimited

/**
 * Spring Cloud Gateway 스타일 Rate Limiter 테스트 컨트롤러
 * 
 * 다양한 Gateway Rate Limiting 시나리오를 테스트할 수 있는 엔드포인트 제공
 */
@RestController
@RequestMapping("/api/v4/gateway")
class GatewayRateLimiterController {

    /**
     * 표준 Gateway Rate Limiting
     * 평균 10TPS, 최대 20요청 버스트 허용
     */
    @GetMapping("/standard")
    @GatewayRateLimited(replenishRate = 10, burstCapacity = 20)
    fun getStandardResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Standard Gateway rate limited resource (10 RPS, burst 20)")
    }

    /**
     * 제한적인 Gateway Rate Limiting
     * 평균 2TPS, 최대 5요청 버스트 허용
     */
    @GetMapping("/restricted")
    @GatewayRateLimited(replenishRate = 2, burstCapacity = 5)
    fun getRestrictedResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Restricted Gateway resource (2 RPS, burst 5)")
    }

    /**
     * 고비용 API 시뮬레이션
     * 평균 5TPS, 최대 10요청 버스트, 요청당 2토큰 소모
     */
    @GetMapping("/expensive")
    @GatewayRateLimited(replenishRate = 5, burstCapacity = 10, requestedTokens = 2)
    fun getExpensiveResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Expensive Gateway resource (5 RPS, burst 10, 2 tokens per request)")
    }

    /**
     * 버스트 허용 API
     * 평균 1TPS, 하지만 최대 30요청까지 버스트 허용 (대용량 배치 처리)
     */
    @GetMapping("/batch")
    @GatewayRateLimited(replenishRate = 1, burstCapacity = 30)
    fun getBatchResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Batch Gateway resource (1 RPS, burst 30 for batch processing)")
    }

    /**
     * 제한 없는 리소스 (비교용)
     */
    @GetMapping("/unlimited")
    fun getUnlimitedResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Unlimited Gateway resource")
    }
}
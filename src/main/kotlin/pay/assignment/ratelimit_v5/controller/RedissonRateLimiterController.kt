package pay.assignment.ratelimit_v5.controller

import org.redisson.api.RateIntervalUnit
import org.redisson.api.RateType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pay.assignment.ratelimit_v5.RedissonRateLimitMode
import pay.assignment.ratelimit_v5.annotation.RedissonRateLimited

/**
 * Redisson RRateLimiter 테스트 컨트롤러
 * 
 * 다양한 Redisson Rate Limiting 시나리오를 테스트할 수 있는 엔드포인트 제공
 * Redisson의 강력한 분산 Rate Limiting 기능들을 시연
 */
@RestController
@RequestMapping("/api/v5/redisson")
class RedissonRateLimiterController {

    /**
     * 기본 Redisson Rate Limiting (PER_CLIENT)
     * 클라이언트별로 독립적인 제한: 초당 5개 요청
     */
    @GetMapping("/basic")
    @RedissonRateLimited(
        rate = 5, 
        rateInterval = 1, 
        rateIntervalUnit = RateIntervalUnit.SECONDS,
        rateType = RateType.PER_CLIENT
    )
    fun getBasicResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Basic Redisson rate limited resource (5 req/sec per client)")
    }

    /**
     * 전체 공유 제한 (OVERALL)
     * 모든 클라이언트가 공유하는 제한: 분당 100개 요청
     */
    @GetMapping("/shared")
    @RedissonRateLimited(
        rate = 100,
        rateInterval = 1,
        rateIntervalUnit = RateIntervalUnit.MINUTES,
        rateType = RateType.OVERALL
    )
    fun getSharedResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Shared Redisson resource (100 req/min across all clients)")
    }

    /**
     * 대기 가능한 Rate Limiting (BLOCKING)
     * 제한에 걸리면 최대 3초까지 대기
     */
    @GetMapping("/blocking")
    @RedissonRateLimited(
        rate = 2,
        rateInterval = 1,
        rateIntervalUnit = RateIntervalUnit.SECONDS,
        mode = RedissonRateLimitMode.BLOCKING,
        maxWaitTimeSeconds = 3
    )
    fun getBlockingResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Blocking Redisson resource (2 req/sec, waits up to 3s)")
    }

    /**
     * 고비용 API (높은 permits)
     * 한 번에 3개의 허가를 소모하는 리소스 집약적 API
     */
    @GetMapping("/expensive")
    @RedissonRateLimited(
        rate = 10,
        rateInterval = 1,
        rateIntervalUnit = RateIntervalUnit.SECONDS,
        permits = 3
    )
    fun getExpensiveResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Expensive Redisson resource (10 permits/sec, costs 3 permits)")
    }

    /**
     * 시간당 제한
     * 시간당 1000개 요청 제한 (대용량 배치 처리용)
     */
    @GetMapping("/hourly")
    @RedissonRateLimited(
        rate = 1000,
        rateInterval = 1,
        rateIntervalUnit = RateIntervalUnit.HOURS
    )
    fun getHourlyResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Hourly limited Redisson resource (1000 req/hour)")
    }

    /**
     * 일일 제한
     * 하루당 10000개 요청 제한 (API 할당량 관리)
     */
    @GetMapping("/daily")
    @RedissonRateLimited(
        rate = 10000,
        rateInterval = 1,
        rateIntervalUnit = RateIntervalUnit.DAYS
    )
    fun getDailyResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Daily limited Redisson resource (10000 req/day)")
    }

    /**
     * 복합 제한 (Multiple permits + Blocking)
     * 5개 허가를 소모하며, 최대 5초까지 대기
     */
    @GetMapping("/complex")
    @RedissonRateLimited(
        rate = 20,
        rateInterval = 1,
        rateIntervalUnit = RateIntervalUnit.SECONDS,
        permits = 5,
        mode = RedissonRateLimitMode.BLOCKING,
        maxWaitTimeSeconds = 5
    )
    fun getComplexResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Complex Redisson resource (20 permits/sec, costs 5, waits up to 5s)")
    }

    /**
     * 제한 없는 리소스 (비교용)
     */
    @GetMapping("/unlimited")
    fun getUnlimitedResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Unlimited Redisson resource")
    }
}
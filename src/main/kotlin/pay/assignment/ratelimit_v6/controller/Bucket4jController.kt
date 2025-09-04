package pay.assignment.ratelimit_v6.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pay.assignment.ratelimit_v6.Bucket4jBandwidthType
import pay.assignment.ratelimit_v6.annotation.Bucket4jLimited
import java.util.concurrent.TimeUnit

/**
 * Bucket4j Rate Limiter 테스트 컨트롤러
 * 
 * Bucket4j의 다양한 기능들을 테스트할 수 있는 엔드포인트 제공
 * Java 생태계의 표준 Rate Limiting 라이브러리 시연
 */
@RestController
@RequestMapping("/api/v6/bucket4j")
class Bucket4jController {

    /**
     * 기본 Simple Bandwidth
     * 1초에 5개 토큰으로 완전히 리필
     */
    @GetMapping("/simple")
    @Bucket4jLimited(capacity = 5, period = 1, timeUnit = TimeUnit.SECONDS)
    fun getSimpleResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Simple Bucket4j resource (5 tokens per second)")
    }

    /**
     * Classic Bandwidth
     * 용량 10개, 1초마다 2개씩 점진적 보충
     */
    @GetMapping("/classic")
    @Bucket4jLimited(
        type = Bucket4jBandwidthType.CLASSIC,
        capacity = 10,
        refillTokens = 2,
        period = 1,
        timeUnit = TimeUnit.SECONDS
    )
    fun getClassicResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Classic Bucket4j resource (capacity: 10, refill: 2/sec)")
    }

    /**
     * 분당 제한
     * 1분에 100개 토큰
     */
    @GetMapping("/minute")
    @Bucket4jLimited(capacity = 100, period = 1, timeUnit = TimeUnit.MINUTES)
    fun getMinuteResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Minute-based Bucket4j resource (100 tokens per minute)")
    }

    /**
     * 시간당 제한
     * 1시간에 3600개 토큰 (초당 1개 평균)
     */
    @GetMapping("/hourly")
    @Bucket4jLimited(capacity = 3600, period = 1, timeUnit = TimeUnit.HOURS)
    fun getHourlyResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Hourly Bucket4j resource (3600 tokens per hour)")
    }

    /**
     * 고비용 API
     * 1초에 10개 토큰, 요청당 3개 소모
     */
    @GetMapping("/expensive")
    @Bucket4jLimited(
        capacity = 10,
        period = 1,
        timeUnit = TimeUnit.SECONDS,
        tokensToConsume = 3
    )
    fun getExpensiveResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Expensive Bucket4j resource (10 tokens/sec, costs 3 tokens)")
    }

    /**
     * 마이크로초 단위 정밀 제어
     * 100밀리초당 1개 토큰 (초당 10개)
     */
    @GetMapping("/precise")
    @Bucket4jLimited(capacity = 1, period = 100, timeUnit = TimeUnit.MILLISECONDS)
    fun getPreciseResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Precise Bucket4j resource (1 token per 100ms)")
    }

    /**
     * 대용량 버스트
     * 1초당 1개 보충, 하지만 용량 100개 (큰 버스트 허용)
     */
    @GetMapping("/burst")
    @Bucket4jLimited(
        type = Bucket4jBandwidthType.CLASSIC,
        capacity = 100,
        refillTokens = 1,
        period = 1,
        timeUnit = TimeUnit.SECONDS
    )
    fun getBurstResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Burst-capable Bucket4j resource (capacity: 100, refill: 1/sec)")
    }

    /**
     * 일일 할당량
     * 하루에 10000개 요청
     */
    @GetMapping("/daily")
    @Bucket4jLimited(capacity = 10000, period = 1, timeUnit = TimeUnit.DAYS)
    fun getDailyResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Daily quota Bucket4j resource (10000 tokens per day)")
    }

    /**
     * 제한 없는 리소스 (비교용)
     */
    @GetMapping("/unlimited")
    fun getUnlimitedResource(): ResponseEntity<String> {
        return ResponseEntity.ok("Unlimited Bucket4j resource")
    }
}
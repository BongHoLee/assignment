package pay.assignment.ratelimit_v2.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pay.assignment.ratelimit_v2.annotation.TokenBucketLimited

@RestController
@RequestMapping("/api/v2")
class RateLimiterController {

    @GetMapping("/limited")
    @TokenBucketLimited(capacity = 5, refillTokens = 1, refillPeriodSeconds = 1)
    fun getLimitedResource(): ResponseEntity<String> {
        return ResponseEntity.ok("This is a token bucket limited resource.")
    }

    @GetMapping("/burst")
    @TokenBucketLimited(capacity = 10, refillTokens = 2, refillPeriodSeconds = 1, tokensPerRequest = 2)
    fun getBurstResource(): ResponseEntity<String> {
        return ResponseEntity.ok("This is a burst-capable resource (costs 2 tokens).")
    }

    @GetMapping("/unlimited")
    fun getUnlimitedResource(): ResponseEntity<String> {
        return ResponseEntity.ok("This is an unlimited resource.")
    }
}
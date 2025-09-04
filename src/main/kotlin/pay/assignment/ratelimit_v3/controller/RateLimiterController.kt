package pay.assignment.ratelimit_v3.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pay.assignment.ratelimit_v3.annotation.SlidingWindowLimited

@RestController
@RequestMapping("/api/v3")
class RateLimiterController {

    @GetMapping("/limited")
    @SlidingWindowLimited(maxRequests = 5, windowSeconds = 10)
    fun getLimitedResource(): ResponseEntity<String> {
        return ResponseEntity.ok("This is a sliding window limited resource.")
    }

    @GetMapping("/precise")
    @SlidingWindowLimited(maxRequests = 3, windowSeconds = 5)
    fun getPreciseResource(): ResponseEntity<String> {
        return ResponseEntity.ok("This is a precisely controlled resource (3 req/5s sliding window).")
    }

    @GetMapping("/unlimited")
    fun getUnlimitedResource(): ResponseEntity<String> {
        return ResponseEntity.ok("This is an unlimited resource.")
    }
}
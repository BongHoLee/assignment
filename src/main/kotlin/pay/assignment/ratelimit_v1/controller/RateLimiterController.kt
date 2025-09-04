package pay.assignment.ratelimit_v1.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pay.assignment.ratelimit_v1.annotation.RateLimited

@RestController
@RequestMapping("/api/v1")
class RateLimiterController {

    @GetMapping("/limited")
    @RateLimited(maxRequest = 3, timeWindowSeconds = 5)
    fun getLimitedResource(): ResponseEntity<String> {
        return ResponseEntity.ok("This is a rate-limited resource.")
    }

    @GetMapping("/unlimited")
    fun getUnlimitedResource(): ResponseEntity<String> {
        return ResponseEntity.ok("This is an unlimited resource.")
    }
}
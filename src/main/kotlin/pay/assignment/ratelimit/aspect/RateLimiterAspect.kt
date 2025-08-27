package pay.assignment.ratelimit.aspect

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.stereotype.Component
import pay.assignment.ratelimit.annotation.RateLimited

@Aspect
@Component
class RateLimiterAspect {

    @Around("@annotation(rateLimited)")
    fun rateLimit(joinPoint: ProceedingJoinPoint, rateLimited: RateLimited): Any? {
        // TODO : 로직을 구현해주세요.


        return joinPoint.proceed()
    }
}
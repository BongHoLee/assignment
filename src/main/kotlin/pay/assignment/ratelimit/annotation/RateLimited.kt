package pay.assignment.ratelimit.annotation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RateLimited(
    val maxRequest: Int = 3, // 최대 요청 횟수
    val timeWindowSeconds: Int = 5 // 시간 창 (초 단위)
)
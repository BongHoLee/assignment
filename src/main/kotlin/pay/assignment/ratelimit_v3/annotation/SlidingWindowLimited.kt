package pay.assignment.ratelimit_v3.annotation

/**
 * Sliding Window 기반 Rate Limiting 어노테이션
 * 
 * Sliding Window는 정확한 시간 윈도우 내에서 요청 수를 제한하여
 * Fixed Window의 경계 효과 문제를 해결합니다.
 * 
 * 사용 예시:
 * @SlidingWindowLimited(maxRequests = 5, windowSeconds = 10)
 * -> 최근 10초 동안 최대 5개 요청 허용
 * 
 * 시간 흐름 예시:
 * 시간:    0s  1s  2s  3s  4s  5s  6s  7s  8s  9s  10s 11s 12s
 * 요청:    X   X   -   X   X   -   -   X   -   -   X   -   -
 * 판단:    OK  OK      OK  OK              NG      OK      OK
 *         (윈도우: 0-10s, 카운트: 4→5)    (윈도우: 1-11s, 카운트: 5, 거부)
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SlidingWindowLimited(
    /**
     * 슬라이딩 윈도우 내 최대 요청 수
     * 이 수치를 초과하는 요청은 거부됨
     */
    val maxRequests: Int = 5,
    
    /**
     * 슬라이딩 윈도우 크기 (초)
     * 이 시간 범위 내의 요청들이 카운트됨
     */
    val windowSeconds: Int = 10
)
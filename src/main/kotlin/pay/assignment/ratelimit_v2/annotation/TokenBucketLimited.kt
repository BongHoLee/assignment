package pay.assignment.ratelimit_v2.annotation

/**
 * Token Bucket 기반 Rate Limiting 어노테이션
 * 
 * 사용 예시:
 * @TokenBucketLimited(capacity = 5, refillTokens = 1, refillPeriodSeconds = 1)
 * -> 최대 5개 토큰 버킷, 1초마다 1개 토큰 보충
 * -> 평상시 초당 1요청, 최대 5요청까지 버스트 허용
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TokenBucketLimited(
    /**
     * 토큰 버킷 최대 용량
     * 버스트 트래픽에서 한번에 처리할 수 있는 최대 요청 수
     */
    val capacity: Int = 5,
    
    /**
     * 보충 주기당 추가되는 토큰 수
     * refillPeriodSeconds 시간마다 이만큼의 토큰이 버킷에 추가됨
     */
    val refillTokens: Int = 1,
    
    /**
     * 토큰 보충 주기 (초)
     * 이 시간마다 refillTokens 개수의 토큰이 버킷에 보충됨
     */
    val refillPeriodSeconds: Int = 1,
    
    /**
     * 한 번의 요청에서 소모할 토큰 수
     * 일반적으로 1이지만, 리소스 집약적인 API는 더 많은 토큰 소모 가능
     */
    val tokensPerRequest: Int = 1
)
package pay.assignment.ratelimit_v4.annotation

/**
 * Spring Cloud Gateway 스타일 Rate Limiting 어노테이션
 * 
 * Spring Cloud Gateway의 RedisRateLimiter와 동일한 방식으로 동작:
 * - Token Bucket 알고리즘 기반
 * - replenishRate: 초당 토큰 보충 속도
 * - burstCapacity: 버킷 최대 용량 (버스트 허용량)
 * - requestedTokens: 요청당 소모 토큰 수
 * 
 * 사용 예시:
 * @GatewayRateLimited(replenishRate = 10, burstCapacity = 20)
 * -> 평균 10TPS, 최대 20요청 버스트 허용
 * 
 * 실제 Gateway와의 차이점:
 * - Gateway는 Reactive WebFlux 환경에서 동작
 * - 이 구현은 Spring MVC 환경에서 Gateway의 알고리즘을 재현
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class GatewayRateLimited(
    /**
     * 초당 토큰 보충 속도
     * 예: replenishRate=10 이면 초당 10개 토큰이 버킷에 추가됨
     * 이는 평균적으로 초당 10개 요청을 허용함을 의미
     */
    val replenishRate: Int = 10,
    
    /**
     * 토큰 버킷의 최대 용량
     * 버스트 트래픽에서 한 번에 처리할 수 있는 최대 요청 수
     * 예: burstCapacity=20 이면 최대 20개 요청까지 연속 처리 가능
     */
    val burstCapacity: Int = 20,
    
    /**
     * 요청당 소모할 토큰 수
     * 리소스 집약적인 API는 더 많은 토큰을 소모하도록 설정 가능
     * 예: requestedTokens=2 이면 한 요청당 2개 토큰 소모
     */
    val requestedTokens: Int = 1
)
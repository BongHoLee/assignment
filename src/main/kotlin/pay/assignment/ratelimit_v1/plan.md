# Redis 기반 Rate Limiter 구현 계획서

## 1. 개요

### 1.1 목적
- 다수 유저가 동시에 사용하는 대규모 시스템에서 사용자별 API 호출 횟수를 제한
- Redis를 활용한 고성능, 고가용성 Rate Limiter 구현
- Spring Boot 환경에서 AOP를 통한 투명한 Rate Limiting 적용

### 1.2 요구사항
- **호출 제한**: 각 사용자는 5초 동안 최대 3번만 API 호출 가능
- **초과 요청 처리**: 제한 횟수 초과 시 HTTP 429 응답과 적절한 에러메시지 반환
- **동시성 보장**: 높은 동시성 환경에서 정확한 카운팅
- **확장성**: 여러 서버 인스턴스에서 상태 공유 가능

## 2. 아키텍처 설계

### 2.1 전체 구조
```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   HTTP Request  │───▶│  RateLimiter    │───▶│     Redis       │
│    (Client)     │    │    Aspect       │    │   (State Store) │
└─────────────────┘    └─────────────────┘    └─────────────────┘
        │                        │                        │
        │                        ▼                        │
        │              ┌─────────────────┐                │
        │              │ RedisRateLimiter│◀───────────────┘
        │              │   (Core Logic)  │
        │              └─────────────────┘
        │                        │
        ▼                        ▼
┌─────────────────┐    ┌─────────────────┐
│   HTTP 429      │    │  Method Execute │
│  (Rate Limited) │    │   (Allowed)     │
└─────────────────┘    └─────────────────┘
```

### 2.2 컴포넌트 구성
1. **RateLimiterAspect**: AOP를 통한 투명한 Rate Limiting 적용
2. **RedisRateLimiter**: Redis 기반 Rate Limiting 핵심 로직
3. **RateLimiterConfig**: Spring Bean 설정 및 의존성 주입
4. **Data Classes**: UserId, RateLimitRule, Decision 등 데이터 구조

## 3. 알고리즘 선택: Fixed Window

### 3.1 선택 이유
- **정확성**: 정확히 지정된 시간 윈도우 내에서 요청 수 제한
- **단순성**: 구현이 간단하고 이해하기 쉬움
- **성능**: Redis의 원자적 연산으로 높은 성능 보장
- **일관성**: 모든 클라이언트가 동일한 윈도우 기준 적용

### 3.2 알고리즘 동작
1. **윈도우 계산**: `windowStart = floor(currentTime / windowSize) * windowSize`
2. **상태 조회**: Redis Hash에서 현재 윈도우의 요청 카운트 조회
3. **윈도우 갱신**: 새로운 윈도우인 경우 카운트 초기화
4. **요청 처리**: 제한 내인 경우 카운트 증가, 초과 시 거부
5. **TTL 설정**: 자동 메모리 정리를 위한 만료 시간 설정

### 3.3 Redis 키 설계
- **키 형식**: `rate_limit:{userId}:{windowSeconds}`
- **데이터 구조**: Hash (windowStart, count)
- **TTL**: windowSize * 2 (여유있는 만료 시간)

## 4. 구현 세부사항

### 4.1 Lua Script 사용 이유
- **원자성**: 모든 Redis 연산이 하나의 트랜잭션으로 실행
- **성능**: 네트워크 라운드트립 최소화
- **동시성**: Race Condition 방지
- **일관성**: 여러 연산의 원자적 실행 보장

### 4.2 사용자 식별 전략
1. **우선순위 1**: `X-User-Id` 헤더
2. **우선순위 2**: `User-Id` 헤더
3. **우선순위 3**: `Authorization` 헤더 (Bearer Token)
4. **기본값**: 클라이언트 IP 주소

### 4.3 에러 처리
- **HTTP Status**: 429 Too Many Requests
- **Error Message**: 명확한 제한 정보와 재시도 시간 포함
- **Headers**: Rate Limit 관련 정보 (선택사항)

## 5. 성능 최적화

### 5.1 Redis 최적화
- **Connection Pool**: RedisTemplate의 커넥션 풀 활용
- **Pipeline**: 단일 Lua Script로 모든 연산 처리
- **Memory**: Hash 구조 사용으로 메모리 효율성 확보
- **TTL**: 자동 만료를 통한 메모리 누수 방지

### 5.2 애플리케이션 최적화
- **AOP**: 메서드 레벨의 투명한 적용
- **Exception**: 빠른 실패 처리로 리소스 절약
- **Logging**: 필요시에만 로깅으로 성능 영향 최소화

## 6. 확장성 고려사항

### 6.1 수평 확장
- **무상태**: 모든 상태가 Redis에 저장되어 서버 인스턴스 추가 용이
- **부하 분산**: 여러 애플리케이션 인스턴스가 동일한 Redis 공유
- **일관성**: 모든 인스턴스에서 동일한 Rate Limiting 적용

### 6.2 Redis 클러스터링
- **샤딩**: 사용자별 키 분산으로 부하 분산
- **복제**: Master-Slave 구조로 고가용성 확보
- **센티널**: 자동 장애 조치 지원

## 7. 모니터링 및 관리

### 7.1 모니터링 지표
- **요청 수**: 시간당/분당 총 요청 수
- **제한 횟수**: Rate Limit으로 인한 거부 횟수
- **응답 시간**: Rate Limiter 처리 시간
- **Redis 성능**: 메모리 사용량, 응답 시간

### 7.2 운영 고려사항
- **설정 변경**: 런타임 설정 변경 기능 (향후 개선)
- **화이트리스트**: 특정 사용자/IP 예외 처리 (향후 개선)
- **통계**: 사용 패턴 분석을 위한 로깅 (향후 개선)

## 8. 테스트 전략

### 8.1 단위 테스트
- RedisRateLimiter 클래스 테스트
- Lua Script 로직 검증
- Decision 계산 로직 테스트

### 8.2 통합 테스트
- Redis와의 연동 테스트
- AOP 동작 검증
- HTTP 응답 코드 확인

### 8.3 성능 테스트
- 동시 요청 처리 능력
- Redis 부하 테스트
- 메모리 누수 검사

## 9. 배포 및 설정

### 9.1 필요 의존성
```kotlin
// build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-data-redis")
implementation("org.springframework.boot:spring-boot-starter-aop")
```

### 9.2 Redis 설정
```yaml
# application.yml
spring:
  redis:
    host: localhost
    port: 6379
    timeout: 2000ms
    lettuce:
      pool:
        max-active: 8
        max-wait: -1ms
        max-idle: 8
        min-idle: 0
```

## 10. 향후 개선 방안

### 10.1 기능 확장
- **Sliding Window**: 더 부드러운 Rate Limiting
- **Token Bucket**: 버스트 트래픽 허용
- **Dynamic Rules**: 런타임 규칙 변경
- **Rate Limit Headers**: 표준 HTTP 헤더 지원

### 10.2 성능 개선
- **Local Cache**: Redis 부하 감소를 위한 로컬 캐싱
- **Async Processing**: 비동기 처리 지원
- **Batch Operations**: 벌크 요청 처리 최적화
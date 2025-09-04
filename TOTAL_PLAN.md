# Rate Limiter 구현 전략 및 계획

## 과제 요구사항 분석
- **기능**: 사용자별(Per User) API 호출 횟수 제한
- **제한 규칙**: 5초 동안 최대 3번 호출
- **제약조건**: 인메모리 기반, 높은 성능과 동시성 고려
- **초과 처리**: 차단 + 적절한 에러메시지

## 현재 코드 분석
### 기존 구현 상태
- `RateLimiter_v0.kt`: FixedWindow 기반 구현체 (이미 완성됨)
- `RateLimiterAspect.kt`: AOP 로직 미완성 (TODO 상태)
- `RateLimitController.kt`: 테스트용 엔드포인트
- `@RateLimited` 어노테이션: 선언적 설정

### 기존 구현의 특징
- **DriftWindowState**: 드래프트 텀블링 방식의 고정 윈도우
- **동시성**: ConcurrentHashMap + synchronized 블록
- **TTL**: 비활성 상태 정리 메커니즘

## 구현 계획 (다양한 방법론)

### v1: Token Bucket Algorithm
**디렉토리**: `ratelimit_v1`
- **방식**: 토큰 버킷 알고리즘
- **특징**: 버스트 트래픽 허용, 부드러운 제한
- **구현**: 토큰 충전율과 버킷 크기 기반

### v2: Sliding Window Log
**디렉토리**: `ratelimit_v2`
- **방식**: 슬라이딩 윈도우 로그
- **특징**: 정확한 요청 시간 기록, 메모리 사용량 높음
- **구현**: 각 요청의 타임스탬프를 큐로 관리

### v3: Sliding Window Counter
**디렉토리**: `ratelimit_v3`
- **방식**: 슬라이딩 윈도우 카운터
- **특징**: 메모리 효율적, 근사치 계산
- **구현**: 이전/현재 윈도우 가중평균

### v4: Distributed Redis-based
**디렉토리**: `ratelimit_v4`
- **방식**: Redis 기반 분산 환경
- **특징**: 다중 서버 환경 지원, 영속성
- **구현**: Redis 원자적 연산 (INCR, EXPIRE)

### v5: Hybrid Approach
**디렉토리**: `ratelimit_v5`
- **방식**: 로컬 캐시 + Redis 백업
- **특징**: 성능과 일관성의 균형
- **구현**: 로컬 우선, 주기적 동기화

## 각 버전별 산출물

### 공통 파일 구조
```
ratelimit_v{N}/
├── plan.md              # 구현 계획 및 설계
├── guide.md             # 면접관용 평가 가이드
├── RateLimiter.kt       # 구현체
├── aspect/
│   └── RateLimiterAspect.kt
└── controller/
    └── TestController.kt (필요시)
```

### plan.md 포함 내용
- 알고리즘 선택 근거
- 동시성 처리 전략
- 메모리 사용량 분석
- 성능 특성

### guide.md 포함 내용
- **WHAT**: 구현한 알고리즘과 핵심 로직
- **WHY**: 해당 방법을 선택한 이유
- **Trade-offs**: 장단점 분석
- **면접 질문**: 예상 질문과 답변 가이드

## 구현 순서
1. **v1 (Token Bucket)**: 가장 직관적이고 일반적인 방법
2. **v2 (Sliding Window Log)**: 정확성이 높은 방법
3. **v3 (Sliding Window Counter)**: 효율성과 정확성의 절충안
4. **v4 (Redis-based)**: 분산 환경 고려
5. **v5 (Hybrid)**: 실제 프로덕션 환경을 고려한 최적화

## 평가 기준
- **정확성**: 요구사항 충족도
- **성능**: 동시성 처리 능력
- **확장성**: 대규모 트래픽 대응
- **코드 품질**: 가독성, 유지보수성
- **설계 사고**: Trade-off에 대한 이해
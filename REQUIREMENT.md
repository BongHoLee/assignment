## context

- 현재 프로젝트 pay_assignment는 1차 기술면접 지원자들이 구현할 '라이브 코딩' 과제이다.
- 우리는 1차 기술면접 '평가자'로써 지원자들이 구현해야 할 과제에 대해 먼저 이해하고 지원자들의 코드를 리뷰할 수 있어야 한다.
- 우리가 봐야 할 과제는 ratelimit 과제이다.
- 라이브코딩 요구사항은 ratelimit/README.md에 명시되어 있다.
- 구현해야 할 템플릿 코드는 src/main/kotlin/pay/assignment/ratelimit 디렉토리에 있다.

## tasks
- ratelimit/README.md에 명시된 요구사항을 완벽히 이해한다.
- ratelimit을 구현하는 여러가지 방법 들 중, 가장 기본적인 방법부터 REDIS를 이용하는 방법까지 다양한 방법으로 구현한다.
- 각 구현 방법은 ratelimit 패키지를 복사해서 ratelimit_v_{number} 형태로 디렉토리를 만들어 구현한다.
- 각 구현에 대한 plan은 해당 패키지의 plan.md에 작성한다.
- 각 구현에 대한 기술면접관용 평가 가이드는 해당 패키지의 guide.md에 작성한다. 이 가이드는 'WAHT', 'WHY'에 대한 내용이 매우 구체적이어야 하고 트레이드 오프에 대해서도 명확히 작성되어야 한다.

## 유의할 점
- 작업을 시작하기 전 TOTAL_PLAN.md 파일을 먼저 작성한 뒤 개발자의 피드백을 기다린다.
- 각 구현(ratelimit_v_{number} 패키지) 마다 완료 후 개발자의 피드백을 기다린다.
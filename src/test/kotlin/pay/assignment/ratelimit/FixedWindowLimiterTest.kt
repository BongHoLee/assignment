package pay.assignment.ratelimit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FixedWindowLimiterTest : FunSpec({

    test("단일 요청이 허용되어야 함") {
        val limiter = FixedWindowLimiter()
        val userId = UserId("user1")
        val rule = RateLimitRule(maxRequest = 5, timeWindowSeconds = 60)

        val decision = limiter.check(userId, rule)

        decision.allowed shouldBe true
        decision.remaining shouldBe 4
        decision.retryAfterMillis shouldBe 0L
    }

    test("제한 내에서 연속 요청이 허용되어야 함") {
        val limiter = FixedWindowLimiter()
        val userId = UserId("user1")
        val rule = RateLimitRule(maxRequest = 3, timeWindowSeconds = 60)

        // 첫 번째 요청
        val decision1 = limiter.check(userId, rule)
        decision1.allowed shouldBe true
        decision1.remaining shouldBe 2

        // 두 번째 요청
        val decision2 = limiter.check(userId, rule)
        decision2.allowed shouldBe true
        decision2.remaining shouldBe 1

        // 세 번째 요청
        val decision3 = limiter.check(userId, rule)
        decision3.allowed shouldBe true
        decision3.remaining shouldBe 0
    }

    test("제한을 초과한 요청이 거부되어야 함") {
        val limiter = FixedWindowLimiter()
        val userId = UserId("user1")
        val rule = RateLimitRule(maxRequest = 2, timeWindowSeconds = 60)

        // 허용된 요청들
        limiter.check(userId, rule).allowed shouldBe true
        limiter.check(userId, rule).allowed shouldBe true

        // 제한 초과 요청
        val rejectedDecision = limiter.check(userId, rule)
        rejectedDecision.allowed shouldBe false
        rejectedDecision.remaining shouldBe 0
        rejectedDecision.retryAfterMillis shouldBeGreaterThan 0L
    }

    test("다른 사용자는 독립적인 제한을 가져야 함") {
        val limiter = FixedWindowLimiter()
        val user1 = UserId("user1")
        val user2 = UserId("user2")
        val rule = RateLimitRule(maxRequest = 2, timeWindowSeconds = 60)

        // user1이 제한까지 요청
        limiter.check(user1, rule).allowed shouldBe true
        limiter.check(user1, rule).allowed shouldBe true
        limiter.check(user1, rule).allowed shouldBe false

        // user2는 여전히 요청 가능
        limiter.check(user2, rule).allowed shouldBe true
        limiter.check(user2, rule).allowed shouldBe true
        limiter.check(user2, rule).allowed shouldBe false
    }

    test("시간 윈도우가 지나면 제한이 리셋되어야 함") {
        val fixedTime = Instant.parse("2025-01-01T00:00:00Z")
        val fixedClock = Clock.fixed(fixedTime, ZoneOffset.UTC)
        val limiter = FixedWindowLimiter(clock = fixedClock)
        val userId = UserId("user1")
        val rule = RateLimitRule(maxRequest = 2, timeWindowSeconds = 60)

        // 첫 번째 윈도우에서 제한까지 요청
        limiter.checkAt(userId, rule, fixedTime).allowed shouldBe true
        limiter.checkAt(userId, rule, fixedTime).allowed shouldBe true
        limiter.checkAt(userId, rule, fixedTime).allowed shouldBe false

        // 윈도우 시간이 지난 후
        val afterWindow = fixedTime.plusSeconds(61)
        val decision = limiter.checkAt(userId, rule, afterWindow)
        decision.allowed shouldBe true
        decision.remaining shouldBe 1
    }

    test("윈도우 경계에서 정확히 동작해야 함") {
        val fixedTime = Instant.parse("2023-01-01T00:00:00Z")
        val fixedClock = Clock.fixed(fixedTime, ZoneOffset.UTC)
        val limiter = FixedWindowLimiter(clock = fixedClock)
        val userId = UserId("user1")
        val rule = RateLimitRule(maxRequest = 1, timeWindowSeconds = 60)

        // 첫 번째 윈도우
        limiter.checkAt(userId, rule, fixedTime).allowed shouldBe true
        limiter.checkAt(userId, rule, fixedTime).allowed shouldBe false

        // 정확히 윈도우 경계 (60초 후)
        val exactBoundary = fixedTime.plusSeconds(60)
        limiter.checkAt(userId, rule, exactBoundary).allowed shouldBe true
    }

    test("retryAfterMillis가 정확히 계산되어야 함") {
        val fixedTime = Instant.parse("2023-01-01T00:00:30Z") // 30초 지점
        val fixedClock = Clock.fixed(fixedTime, ZoneOffset.UTC)
        val limiter = FixedWindowLimiter(clock = fixedClock)
        val userId = UserId("user1")
        val rule = RateLimitRule(maxRequest = 1, timeWindowSeconds = 60)

        // 제한까지 요청
        limiter.checkAt(userId, rule, fixedTime).allowed shouldBe true
        
        // 제한 초과 요청
        val rejectedDecision = limiter.checkAt(userId, rule, fixedTime.plusSeconds(20))
        rejectedDecision.allowed shouldBe false
        
        // 다음 윈도우까지 60초 남음 (윈도우는 30초에 시작했으므로 90초에 끝남)
        // reject 요청은 50초(30 + 20)에 발생했으므로, 90 - 50 = 40초 대기 필요
        rejectedDecision.retryAfterMillis shouldBe 40000L
    }

    test("같은 사용자의 다른 윈도우 크기는 독립적이어야 함") {
        val limiter = FixedWindowLimiter()
        val userId = UserId("user1")
        val rule60 = RateLimitRule(maxRequest = 1, timeWindowSeconds = 60)
        val rule120 = RateLimitRule(maxRequest = 1, timeWindowSeconds = 120)

        // 60초 윈도우에서 제한 도달
        limiter.check(userId, rule60).allowed shouldBe true
        limiter.check(userId, rule60).allowed shouldBe false

        // 120초 윈도우는 여전히 사용 가능
        limiter.check(userId, rule120).allowed shouldBe true
        limiter.check(userId, rule120).allowed shouldBe false
    }

    test("cleanupExpired가 오래된 상태를 제거해야 함") {
        val fixedTime = Instant.parse("2023-01-01T00:00:00Z")
        val fixedClock = Clock.fixed(fixedTime, ZoneOffset.UTC)
        val limiter = FixedWindowLimiter(clock = fixedClock)
        val userId = UserId("user1")
        val rule = RateLimitRule(maxRequest = 1, timeWindowSeconds = 60)

        // 첫 번째 요청으로 상태 생성
        limiter.checkAt(userId, rule, fixedTime).allowed shouldBe true
        limiter.checkAt(userId, rule, fixedTime).allowed shouldBe false

        // TTL보다 오래된 시간으로 cleanup 실행
        val afterCleanupTime = fixedTime.plusSeconds(600)
        limiter.cleanupExpired(idleMillis = 300000L, now = afterCleanupTime) // 5분 TTL

        // cleanup 후에는 새로운 윈도우에서 다시 요청이 가능해야 함
        // (상태가 청소되었다면 새로운 상태로 시작)
        val afterCleanupDecision = limiter.checkAt(userId, rule, afterCleanupTime)
        afterCleanupDecision.allowed shouldBe true
        afterCleanupDecision.remaining shouldBe 0 // maxRequest가 1이므로
    }

    test("DriftWindowState 초기화가 올바르게 동작해야 함") {
        val nowMs = 1000L
        val state = DriftWindowState.init(nowMs)

        state.windowStartMs shouldBe nowMs
        state.count shouldBe 0
        state.lastSeenMillis shouldBe nowMs
    }

    test("DriftWindowState 결정 로직이 올바르게 동작해야 함") {
        val nowMs = 1000L
        val state = DriftWindowState.init(nowMs)
        val windowMs = 60000L
        val limit = 2

        // 첫 번째 요청
        val decision1 = state.decide(nowMs, windowMs, limit)
        decision1.allowed shouldBe true
        decision1.remaining shouldBe 1
        decision1.retryAfterMillis shouldBe 0L

        // 두 번째 요청
        val decision2 = state.decide(nowMs + 100, windowMs, limit)
        decision2.allowed shouldBe true
        decision2.remaining shouldBe 0
        decision2.retryAfterMillis shouldBe 0L

        // 세 번째 요청 (제한 초과)
        val decision3 = state.decide(nowMs + 200, windowMs, limit)
        decision3.allowed shouldBe false
        decision3.remaining shouldBe 0
        decision3.retryAfterMillis shouldBeGreaterThan 0L
    }

    test("윈도우 롤링이 올바르게 동작해야 함") {
        val nowMs = 1000L
        val state = DriftWindowState.init(nowMs)
        val windowMs = 60000L
        val limit = 1

        // 첫 번째 윈도우에서 제한 도달
        state.decide(nowMs, windowMs, limit).allowed shouldBe true
        state.decide(nowMs + 100, windowMs, limit).allowed shouldBe false

        // 새 윈도우에서 다시 허용
        val newWindowTime = nowMs + windowMs + 1000
        val decision = state.decide(newWindowTime, windowMs, limit)
        decision.allowed shouldBe true
        decision.remaining shouldBe 0

        // 윈도우 시작 시간이 업데이트되었는지 확인
        state.windowStartMs shouldBe newWindowTime
        state.count shouldBe 1
    }

    test("동시성 테스트 - 여러 스레드에서 안전하게 동작해야 함") {
        val limiter = FixedWindowLimiter()
        val userId = UserId("user1")
        val rule = RateLimitRule(maxRequest = 100, timeWindowSeconds = 60)
        
        val threads = mutableListOf<Thread>()
        val results = mutableListOf<Decision>()
        val resultsLock = Any()

        // 10개 스레드에서 각각 10번씩 요청
        repeat(10) { _ ->
            val thread = Thread {
                repeat(10) {
                    val decision = limiter.check(userId, rule)
                    synchronized(resultsLock) {
                        results.add(decision)
                    }
                }
            }
            threads.add(thread)
            thread.start()
        }

        // 모든 스레드 완료 대기
        threads.forEach { it.join() }

        // 결과 검증
        results.size shouldBe 100
        val allowedCount = results.count { it.allowed }
        allowedCount shouldBe 100 // 모든 요청이 제한 내에서 허용되어야 함
    }

})

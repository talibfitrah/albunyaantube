import Foundation
import Testing
@testable import InnerTubeKit

@Suite struct SessionStoreTests {
    @Test func rotateThrottlesToOncePerTenMinutes() async {
        let clock = ManualClock()
        let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())

        #expect(await store.rotate(.visionos) == true)
        #expect(await store.rotate(.visionos) == false)

        clock.advance(by: .seconds(600))
        #expect(await store.rotate(.visionos) == true)
    }

    @Test func rotateClearsVisitorDataForThatFamily() async {
        let clock = ManualClock()
        let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())

        await store.setVisitorData("abc123", for: .visionos)
        #expect(await store.visitorData(for: .visionos) == "abc123")

        _ = await store.rotate(.visionos)
        #expect(await store.visitorData(for: .visionos) == nil)
    }

    @Test func visitorDataIsPerFamily() async {
        let clock = ManualClock()
        let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())

        await store.setVisitorData("visionos-token", for: .visionos)
        #expect(await store.visitorData(for: .visionos) == "visionos-token")
        #expect(await store.visitorData(for: .web) == nil)
    }

    // MARK: - back-off: consecutive failed bot-checked walks, minutes never hours (spec R10)

    @Test func oneFailedBotCheckedWalkBlocksNothing() async {
        let clock = ManualClock()
        let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())

        await store.recordBotCheck()

        #expect(await store.cooldownRemaining(now: clock.wallNow) == nil)
    }

    @Test func consecutiveTripsBackOffOneTwoFiveMinutesAndNeverLonger() async {
        let clock = ManualClock()
        let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())

        var remaining: [Duration?] = []
        for _ in 0..<6 {
            await store.recordBotCheck()
            remaining.append(await store.cooldownRemaining(now: clock.wallNow))
            clock.advanceWall(by: .seconds(1))
        }

        #expect(remaining == [nil, .seconds(60), .seconds(120), .seconds(300), .seconds(300), .seconds(300)])
    }

    @Test func aCleanFetchEndsTheStreakAndLiftsTheCooldown() async {
        let clock = ManualClock()
        let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())
        await store.recordBotCheck()
        await store.recordBotCheck()
        #expect(await store.cooldownRemaining(now: clock.wallNow) == .seconds(60))

        await store.recordSuccess()

        #expect(await store.cooldownRemaining(now: clock.wallNow) == nil)
        #expect(await store.loadCooldown().tripCount == 0)
        await store.recordBotCheck()                                         // first of a NEW streak
        #expect(await store.cooldownRemaining(now: clock.wallNow) == nil)
    }

    @Test func tripsMoreThanThirtyMinutesApartAreNotConsecutive() async {
        let clock = ManualClock()
        let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())
        await store.recordBotCheck()

        clock.advanceWall(by: .seconds(31 * 60))
        await store.recordBotCheck()

        #expect(await store.cooldownRemaining(now: clock.wallNow) == nil)
    }

    /// Review Focus 3. A 1.0.2 install persisted up to 24 h under this exact key and shape (the
    /// upgrade contract -- `ManualClock`'s wall starts at reference date 0, so `until` 86400 is a day
    /// out). 1.0.3 must block for 5 minutes at most.
    @Test func anOldBuildsDayLongCooldownIsClampedToFiveMinutes() async {
        let clock = ManualClock()
        let keyValueStore = InMemoryKeyValueStore()
        keyValueStore.set("InnerTubeKit.SessionStore.cooldown",
                          Data(#"{"until":86400,"tripCount":4,"lastTrip":0,"cleanStreakStart":0}"#.utf8))
        let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: keyValueStore)

        #expect(await store.cooldownRemaining(now: clock.wallNow) == .seconds(300))
    }

    /// The clamp must bound the DEADLINE, not just the remaining time: a 24 h `until` clamped only on
    /// read would say "300 s left" all day and block exactly as 1.0.2 did.
    @Test func anOldBuildsDayLongCooldownCountsDownAndEndsAfterFiveMinutes() async {
        let clock = ManualClock()
        let keyValueStore = InMemoryKeyValueStore()
        keyValueStore.set("InnerTubeKit.SessionStore.cooldown",
                          Data(#"{"until":86400,"tripCount":4,"lastTrip":0,"cleanStreakStart":0}"#.utf8))
        let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: keyValueStore)

        clock.advanceWall(by: .seconds(100))
        #expect(await store.cooldownRemaining(now: clock.wallNow) == .seconds(200))
        clock.advanceWall(by: .seconds(201))
        #expect(await store.cooldownRemaining(now: clock.wallNow) == nil)
    }

    /// R10: reads never exceed 5 minutes. A wall clock set BACK past the trip (a device clock that was
    /// ahead and got corrected) would otherwise read hours, and nothing clears it: `performResolve`
    /// throws `.cooldown` before any walk, so no `recordSuccess` can run. Fail open.
    @Test(arguments: [2 * 3600, 26 * 3600])
    func aWallClockSetBackPastTheTripNeverReadsMoreThanFiveMinutes(setBack: Int) async {
        let clock = ManualClock()
        clock.wallNow = Date(timeIntervalSinceReferenceDate: 30 * 3600)
        let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())
        await store.recordBotCheck()
        await store.recordBotCheck()
        #expect(await store.cooldownRemaining(now: clock.wallNow) == .seconds(60))

        clock.wallNow = clock.wallNow.addingTimeInterval(-Double(setBack))

        let remaining = await store.cooldownRemaining(now: clock.wallNow)
        #expect(remaining == nil || remaining! <= .seconds(300))
        #expect(remaining == nil)   // fail open: a legitimate read is never more than 300 s
    }

    /// A record with no `lastTrip` cannot bound its own deadline from the trip, so it must not be
    /// trusted for hours either.
    @Test func aRecordWithNoLastTripCannotBlockForHours() async {
        let clock = ManualClock()
        let keyValueStore = InMemoryKeyValueStore()
        keyValueStore.set("InnerTubeKit.SessionStore.cooldown", Data(#"{"until":86400,"tripCount":4}"#.utf8))
        let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: keyValueStore)

        #expect(await store.cooldownRemaining(now: clock.wallNow) == nil)
    }

    /// The window is strict: 29:59 apart is consecutive, exactly 30:00 is not.
    @Test func theThirtyMinuteWindowIsStrict() async {
        for (gap, expectedCount) in [(30 * 60 - 1, 2), (30 * 60, 1)] {
            let clock = ManualClock()
            let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())
            await store.recordBotCheck()
            clock.advanceWall(by: .seconds(gap))
            await store.recordBotCheck()
            #expect(await store.loadCooldown().tripCount == expectedCount, "gap \(gap) s")
        }
    }

    @Test func cooldownRemainingIsNilWhenNeverTripped() async {
        let clock = ManualClock()
        let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())

        #expect(await store.cooldownRemaining(now: clock.wallNow) == nil)
    }
}

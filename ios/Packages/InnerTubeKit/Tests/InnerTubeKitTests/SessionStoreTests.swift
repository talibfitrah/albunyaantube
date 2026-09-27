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

    @Test func thirdBotCheckWithin24HoursEscalatesToTwelveHours() async {
        let clock = ManualClock()
        let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())

        await store.recordBotCheck() // 1st -> 1h
        clock.advanceWall(by: .seconds(60))
        await store.recordBotCheck() // 2nd -> 4h
        clock.advanceWall(by: .seconds(60))
        await store.recordBotCheck() // 3rd -> 12h

        let remaining = await store.cooldownRemaining(now: clock.wallNow)
        #expect(remaining != nil)
        #expect(remaining! <= .seconds(12 * 3600))
        #expect(remaining! >= .seconds(12 * 3600 - 5))
    }

    @Test func successSevenDaysAfterLastTripResetsEscalation() async {
        // Proves recordSuccess() itself resets tripCount -- reads the persisted
        // record directly rather than inferring it from a later trip, since a
        // later trip >24h after the prior one self-resets independently of
        // recordSuccess() (tripWindow 24h < cleanResetWindow 7d would otherwise
        // make this test pass even with recordSuccess() as a no-op).
        let clock = ManualClock()
        let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())

        await store.recordBotCheck() // trip count 1
        clock.advanceWall(by: .seconds(7 * 24 * 3600))
        await store.recordSuccess() // 7 clean days -> reset trip count to 0

        #expect(await store.loadCooldown().tripCount == 0)
    }

    @Test func cooldownRemainingIsNilWhenNeverTripped() async {
        let clock = ManualClock()
        let store = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())

        #expect(await store.cooldownRemaining(now: clock.wallNow) == nil)
    }
}

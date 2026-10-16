import Foundation

/// Owns InnerTube session hygiene per client family (`extraction.md` §3.5,
/// §6.3): per-family `visitorData`, throttled rotation, and the persisted
/// bot-check cooldown that survives app restarts.
///
/// The 10-minute rotation guard is process-lifetime only (monotonic clock,
/// never persisted — a fresh process naturally starts unthrottled). The
/// cooldown is wall-clock and persisted via `KeyValueStore`; since 1.0.3 it is minutes, never hours
/// (owner directive 2026-10-14).
public actor SessionStore {
    private static let cooldownKey = "InnerTubeKit.SessionStore.cooldown"
    private static let visitorDataKeyPrefix = "InnerTubeKit.SessionStore.visitorData."

    private static let rotationMinInterval: Duration = .seconds(600)
    /// Consecutive failed bot-checked walks: the 1st arms nothing (the rung already re-bootstrapped
    /// and retried), then 1 / 2 / 5 min. Owner directive 2026-10-14: never block playback for hours.
    private static let cooldownDurations: [TimeInterval] = [0, 60, 120, 300]
    /// Also the read bound: a 1.0.2 install persisted up to 24 h under `cooldownKey`.
    private static let maxCooldown: TimeInterval = 300
    /// Trips further apart than this are not consecutive.
    private static let tripWindow: TimeInterval = 30 * 60

    struct CooldownRecord: Codable, Sendable {
        var until: Date?
        var tripCount: Int = 0
        var lastTrip: Date?
    }

    private let monotonicClock: MonotonicClock
    private let wallClock: WallClock
    private let keyValueStore: KeyValueStore
    private var lastRotatedAt: [String: Duration] = [:]

    public init(monotonicClock: MonotonicClock, wallClock: WallClock, keyValueStore: KeyValueStore) {
        self.monotonicClock = monotonicClock
        self.wallClock = wallClock
        self.keyValueStore = keyValueStore
    }

    // MARK: - visitorData

    public func visitorData(for family: ClientFamily) -> String? {
        guard let data = keyValueStore.get(visitorDataKey(family)), !data.isEmpty else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public func setVisitorData(_ value: String, for family: ClientFamily) {
        keyValueStore.set(visitorDataKey(family), Data(value.utf8))
    }

    // MARK: - rotation

    /// At most one rotation per family per 10 min (`ios-app-plan.md:122`).
    /// Clears that family's visitorData on success so the next call fetches a fresh one.
    public func rotate(_ family: ClientFamily) -> Bool {
        let key = familyKey(family)
        let now = monotonicClock.now
        if let last = lastRotatedAt[key], now - last < Self.rotationMinInterval {
            return false
        }
        lastRotatedAt[key] = now
        // ponytail: empty Data signals "cleared" rather than adding a delete op to KeyValueStore.
        keyValueStore.set(visitorDataKey(family), Data())
        return true
    }

    // MARK: - cooldown

    /// Records one failed walk that saw a bot check. Escalates over CONSECUTIVE trips (each within
    /// `tripWindow` of the last): 1st -> none, 2nd -> 60 s, 3rd -> 120 s, 4th+ -> 300 s.
    public func recordBotCheck() {
        let now = wallClock.wallNow
        var record = loadCooldown()
        let consecutive = record.lastTrip.map { now.timeIntervalSince($0) < Self.tripWindow } ?? false
        let tripCount = consecutive ? record.tripCount + 1 : 1
        record.until = now.addingTimeInterval(Self.cooldownDurations[min(tripCount, Self.cooldownDurations.count) - 1])
        record.tripCount = tripCount
        record.lastTrip = now
        saveCooldown(record)
    }

    /// Any clean fetch ends the streak and lifts the cooldown. Writes only when there is something
    /// to clear -- this runs on every successful resolve.
    public func recordSuccess() {
        let record = loadCooldown()
        guard record.tripCount > 0 || record.until != nil else { return }
        saveCooldown(CooldownRecord())
    }

    /// R10: a read is never more than `maxCooldown`. A record a 1.0.2 install persisted can say up
    /// to 24 h, so the DEADLINE is bounded to `maxCooldown` after the trip (clamping only the
    /// remaining time would read "300 s left" all day). A legitimate remaining time can then never
    /// exceed `maxCooldown`, so one that does means the wall clock moved back past the trip (or the
    /// record has no `lastTrip`): fail open, because nothing else could clear it -- `performResolve`
    /// throws `.cooldown` before any walk, so no `recordSuccess` runs.
    public func cooldownRemaining(now: Date) -> Duration? {
        let record = loadCooldown()
        guard let until = record.until else { return nil }
        let deadline = min(until, (record.lastTrip ?? until).addingTimeInterval(Self.maxCooldown))
        let remaining = deadline.timeIntervalSince(now)
        guard remaining > 0, remaining <= Self.maxCooldown else { return nil }
        return .seconds(Int(remaining.rounded(.up)))
    }

    // MARK: - private helpers

    private func familyKey(_ family: ClientFamily) -> String {
        switch family {
        case .visionos: return "visionos"
        case .android: return "android"
        case .web: return "web"
        }
    }

    private func visitorDataKey(_ family: ClientFamily) -> String {
        Self.visitorDataKeyPrefix + familyKey(family)
    }

    func loadCooldown() -> CooldownRecord {
        guard let data = keyValueStore.get(Self.cooldownKey),
            let record = try? JSONDecoder().decode(CooldownRecord.self, from: data)
        else {
            return CooldownRecord()
        }
        return record
    }

    private func saveCooldown(_ record: CooldownRecord) {
        guard let data = try? JSONEncoder().encode(record) else { return }
        keyValueStore.set(Self.cooldownKey, data)
    }
}

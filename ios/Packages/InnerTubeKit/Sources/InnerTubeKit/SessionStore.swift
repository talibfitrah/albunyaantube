import Foundation

/// Owns InnerTube session hygiene per client family (`extraction.md` §3.5,
/// §6.3): per-family `visitorData`, throttled rotation, and the persisted
/// bot-check cooldown that survives app restarts.
///
/// The 10-minute rotation guard is process-lifetime only (monotonic clock,
/// never persisted — a fresh process naturally starts unthrottled). The
/// cooldown escalation/reset is wall-clock and persisted via `KeyValueStore`,
/// mirroring Android's `CooldownState.kt`.
public actor SessionStore {
    private static let cooldownKey = "InnerTubeKit.SessionStore.cooldown"
    private static let visitorDataKeyPrefix = "InnerTubeKit.SessionStore.visitorData."

    private static let rotationMinInterval: Duration = .seconds(600)
    private static let cooldownDurations: [TimeInterval] = [3600, 4 * 3600, 12 * 3600, 24 * 3600]
    private static let tripWindow: TimeInterval = 24 * 3600
    private static let cleanResetWindow: TimeInterval = 7 * 24 * 3600

    struct CooldownRecord: Codable, Sendable {
        var until: Date?
        var tripCount: Int = 0
        var lastTrip: Date?
        var cleanStreakStart: Date?
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

    /// Records a bot-check/429 trip. Escalates by trip count within a rolling
    /// 24 h window: 1st -> 1 h, 2nd -> 4 h, 3rd -> 12 h, 4th+ -> 24 h
    /// (`extraction.md` §6.3).
    public func recordBotCheck() {
        let now = wallClock.wallNow
        var record = loadCooldown()
        let withinWindow = record.lastTrip.map { now.timeIntervalSince($0) < Self.tripWindow } ?? false
        let tripCount = withinWindow ? record.tripCount + 1 : 1
        let durationIndex = min(tripCount - 1, Self.cooldownDurations.count - 1)
        record.until = now.addingTimeInterval(Self.cooldownDurations[durationIndex])
        record.tripCount = tripCount
        record.lastTrip = now
        record.cleanStreakStart = now
        saveCooldown(record)
    }

    /// Records a clean (non-tripped) fetch. If 7 days have elapsed since the
    /// last trip, resets the trip count so the next trip starts back at 1 h.
    public func recordSuccess() {
        let now = wallClock.wallNow
        var record = loadCooldown()
        guard let streakStart = record.cleanStreakStart,
            now.timeIntervalSince(streakStart) >= Self.cleanResetWindow
        else { return }
        record.tripCount = 0
        record.cleanStreakStart = now
        saveCooldown(record)
    }

    public func cooldownRemaining(now: Date) -> Duration? {
        guard let until = loadCooldown().until, until > now else { return nil }
        return .seconds(Int(until.timeIntervalSince(now).rounded(.up)))
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

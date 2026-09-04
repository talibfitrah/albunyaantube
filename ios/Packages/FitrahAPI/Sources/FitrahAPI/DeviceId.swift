import Foundation

/// Random per-install identifier sent as `X-Device-Id` on every backend call.
/// Mirrors Android's `device_prefs/device_id` (NetworkModule.kt).
///
/// `value` is RESOLVED PER READ, not captured at construction (Stage 3 / M5). Every client
/// (`AccountClient`, `OfflineGateClient`, `IndexClient`, `ReportClient`, `DeviceIdMiddleware`)
/// takes one of these at container construction and keeps it for the life of the process, so a
/// stored `String` meant `LocalAccountWiper`'s step 6 — remove the defaults key so the next request
/// mints a new id (CF-A-9) — had no effect until relaunch: every request for the rest of the
/// session still carried the deleted account's id. Reading through the closure fixes that once, at
/// the source, for all five clients instead of five times at the call sites.
public struct DeviceId: Sendable, Equatable {
    public static let defaultsKey = "com.albunyaan.tube.deviceId"

    private let read: @Sendable () -> String

    /// The id this request should carry. A `persisted(in:)` id re-reads (and, if the key is gone,
    /// re-mints) here; a literal one always answers its literal.
    public var value: String { read() }

    public init(value: String) { read = { value } }

    private init(read: @escaping @Sendable () -> String) { self.read = read }

    public static func persisted(in defaults: UserDefaults = .standard) -> DeviceId {
        // `UserDefaults` carries no `Sendable` conformance but is documented as thread-safe, and
        // the app already shares one instance across every store and client. The alternative —
        // capturing the suite name and rebuilding the object per read — would allocate on every
        // request and silently fall back to `.standard` for a nil suite.
        nonisolated(unsafe) let defaults = defaults
        return DeviceId {
            if let existing = defaults.string(forKey: defaultsKey) { return existing }
            let created = UUID().uuidString
            defaults.set(created, forKey: defaultsKey)
            return created
        }
    }

    /// A closure has no identity to compare, so equality is what the header would carry.
    public static func == (lhs: DeviceId, rhs: DeviceId) -> Bool { lhs.value == rhs.value }
}

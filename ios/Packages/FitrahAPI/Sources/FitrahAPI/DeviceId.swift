import Foundation

/// Random per-install identifier sent as `X-Device-Id` on every backend call.
/// Mirrors Android's `device_prefs/device_id` (NetworkModule.kt).
public struct DeviceId: Sendable, Equatable {
    public static let defaultsKey = "com.albunyaan.tube.deviceId"
    public let value: String

    public init(value: String) { self.value = value }

    public static func persisted(in defaults: UserDefaults = .standard) -> DeviceId {
        if let existing = defaults.string(forKey: defaultsKey) {
            return DeviceId(value: existing)
        }
        let created = UUID().uuidString
        defaults.set(created, forKey: defaultsKey)
        return DeviceId(value: created)
    }
}

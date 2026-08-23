import Foundation
import Testing
@testable import FitrahAPI

struct DeviceIdTests {
    private func freshDefaults() -> UserDefaults {
        let name = "DeviceIdTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    @Test func createsUUIDOnFirstUseAndPersistsIt() {
        let defaults = freshDefaults()
        let first = DeviceId.persisted(in: defaults)
        let second = DeviceId.persisted(in: defaults)
        #expect(UUID(uuidString: first.value) != nil)
        #expect(first.value == second.value)
        #expect(defaults.string(forKey: "device_id") == first.value)
    }

    @Test func reusesExistingValue() {
        let defaults = freshDefaults()
        defaults.set("existing-id", forKey: "device_id")
        #expect(DeviceId.persisted(in: defaults).value == "existing-id")
    }
}

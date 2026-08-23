import Foundation
import Testing
@testable import FitrahAPI

@Suite(.perTest)
struct DeviceIdTests {
    private func freshDefaults() -> (defaults: UserDefaults, name: String) {
        let name = "DeviceIdTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return (defaults, name)
    }

    @Test func createsUUIDOnFirstUseAndPersistsIt() {
        let (defaults, name) = freshDefaults()
        defer { defaults.removePersistentDomain(forName: name) }
        let first = DeviceId.persisted(in: defaults)
        let second = DeviceId.persisted(in: defaults)
        #expect(UUID(uuidString: first.value) != nil)
        #expect(first.value == second.value)
        #expect(defaults.string(forKey: DeviceId.defaultsKey) == first.value)
    }

    @Test func reusesExistingValue() {
        let (defaults, name) = freshDefaults()
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.set("existing-id", forKey: DeviceId.defaultsKey)
        #expect(DeviceId.persisted(in: defaults).value == "existing-id")
    }
}

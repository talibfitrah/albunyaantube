import Foundation
import Synchronization
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

    /// Holds the FIRST write open, so the check-then-act window inside `persisted(in:)` is a
    /// rendezvous instead of the few microseconds no test can schedule into. Everything else is the
    /// real suite.
    private final class GatedDefaults: UserDefaults, @unchecked Sendable {
        /// Signalled once a first read has decided to mint and is about to store its value.
        let minting = DispatchSemaphore(value: 0)
        /// The test releases that write when it is done watching.
        let proceed = DispatchSemaphore(value: 0)
        private let lock = NSLock()
        private var firstWrite = true
        private var readCount = 0

        /// How many reads have reached the suite — i.e. how many callers got past `DeviceId`'s own
        /// synchronisation, if it has any.
        var reads: Int { lock.withLock { readCount } }

        override func string(forKey key: String) -> String? {
            lock.withLock { readCount += 1 }
            return super.string(forKey: key)
        }

        override func set(_ value: Any?, forKey key: String) {
            let isFirst = lock.withLock { () -> Bool in
                let first = firstWrite
                firstWrite = false
                return first
            }
            if isFirst {
                minting.signal()
                proceed.wait()
            }
            super.set(value, forKey: key)
        }
    }

    /// Stage 7 fix 2 / M7: the app builds five clients at launch and they read this concurrently.
    /// With an unsynchronised read-or-create, a second reader arriving while the first is between
    /// its `string(forKey:)` and its `set` sees an empty suite too, mints its own UUID and stores
    /// it — two in-flight requests carrying different `X-Device-Id` values, and one id written over
    /// the other. One value, one stored key, however many readers arrive.
    ///
    /// Stage 9 round 3 / R3-P2: two `DeviceId.persisted(in:)` INSTANCES, because that is what the
    /// container builds — one per client, five in all. The M7 lock was per instance, so it
    /// serialised each client against itself and nothing against the other four: the race it
    /// claimed to close was still open on the only shape that can reach it.
    ///
    /// No clock anywhere: the first reader is parked at its write, the second is given a bounded
    /// budget of spins to reach the suite (unsynchronised it arrives at once; under the lock it
    /// never arrives, which is the property), and the parked writer is then released.
    @Test func concurrentFirstReadsMintExactlyOneId() {
        let name = "DeviceIdTests-\(UUID().uuidString)"
        let defaults = GatedDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        defer { defaults.removePersistentDomain(forName: name) }
        // Two separate instances over one empty suite — `persisted(in:)` allocates a new store per
        // call, so this is the container's shape and not one client racing itself.
        let clients = [DeviceId.persisted(in: defaults), DeviceId.persisted(in: defaults)]

        let values = Mutex<[String]>([])
        let finished = DispatchSemaphore(value: 0)
        for deviceId in clients {
            DispatchQueue.global().async {
                let value = deviceId.value
                values.withLock { $0.append(value) }
                finished.signal()
            }
        }

        defaults.minting.wait()
        var spins = 0
        while spins < 5_000_000, defaults.reads < 2 { spins += 1 }
        defaults.proceed.signal()
        finished.wait()
        finished.wait()

        let read = Set(values.withLock { $0 })
        #expect(read.count == 1, "concurrent first reads minted more than one device id: \(read)")
        #expect(read.first == defaults.string(forKey: DeviceId.defaultsKey),
                "the id the requests carried is not the one that was stored")
    }

    @Test func reusesExistingValue() {
        let (defaults, name) = freshDefaults()
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.set("existing-id", forKey: DeviceId.defaultsKey)
        #expect(DeviceId.persisted(in: defaults).value == "existing-id")
    }
}

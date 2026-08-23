import Foundation
import Testing
@testable import FitrahAPI

@Suite(.perTest)
struct FitrahAPIClientTests {
    @Test func defaultSessionConfigurationHasBoundedTimeoutsAndNoCache() {
        let config = FitrahAPIClient.defaultSessionConfiguration
        #expect(config.timeoutIntervalForRequest == 20)
        #expect(config.timeoutIntervalForResource == 120)
        #expect(config.urlCache == nil)
        #expect(config.waitsForConnectivity == false)
    }

    @Test func defaultTransportReusesOneSharedSession() {
        let a = FitrahAPIClient.defaultTransport()
        let b = FitrahAPIClient.defaultTransport()
        #expect(a.configuration.session === b.configuration.session)
    }
}

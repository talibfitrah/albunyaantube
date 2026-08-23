import Foundation
import Testing
@testable import FitrahAPI

@Suite(.perTest)
struct FitrahAPIClientTests {
    @Test func defaultSessionConfigurationHasBoundedTimeoutsAndNoCache() {
        let config = FitrahAPIClient.defaultSessionConfiguration
        #expect(config.timeoutIntervalForRequest == 15)
        #expect(config.timeoutIntervalForResource == 20)
        #expect(config.urlCache == nil)
        #expect(config.waitsForConnectivity == false)
    }
}

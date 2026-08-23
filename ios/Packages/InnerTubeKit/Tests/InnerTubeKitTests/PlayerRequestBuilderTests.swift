import Foundation
import Testing
@testable import InnerTubeKit

@Suite struct PlayerRequestBuilderTests {
    private let builder = PlayerRequestBuilder()
    private let locale = InnerTubeLocale(hl: "ar", gl: "MA")

    @Test func visionOSBuildCarriesVisitorDataInBodyAndHeaderAndInjectedLocale() throws {
        let context = RemoteConfig.bundledDefault.clients["visionos"]!
        let request = builder.build(
            videoId: "dQw4w9WgXcQ", family: .visionos, context: context, visitorData: "VISITOR123", locale: locale)

        #expect(request.method == "POST")
        #expect(request.url.absoluteString == "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false")
        #expect(request.headers["X-Goog-Visitor-Id"] == "VISITOR123")
        #expect(request.headers["User-Agent"] == context.userAgent)
        #expect(request.headers["X-YouTube-Client-Name"] == "101")
        #expect(request.headers["X-YouTube-Client-Version"] == "1.02")

        let client = try clientDict(from: request.body)
        #expect(client["clientName"] as? String == "VISIONOS")
        #expect(client["visitorData"] as? String == "VISITOR123")
        #expect(client["hl"] as? String == "ar")
        #expect(client["gl"] as? String == "MA")
        #expect(client["hl"] as? String != "US")
        #expect(client["gl"] as? String != "US")
    }

    @Test func identicalInputsProduceByteIdenticalBodies() {
        let context = RemoteConfig.bundledDefault.clients["visionos"]!
        let request1 = builder.build(
            videoId: "dQw4w9WgXcQ", family: .visionos, context: context, visitorData: "VISITOR123", locale: locale)
        let request2 = builder.build(
            videoId: "dQw4w9WgXcQ", family: .visionos, context: context, visitorData: "VISITOR123", locale: locale)

        #expect(request1.body == request2.body)
    }

    @Test func webBuildOmitsDeviceFieldsAndUserAgentHeader() throws {
        let context = RemoteConfig.bundledDefault.clients["web"]!
        #expect(context.userAgent == nil)
        let request = builder.build(
            videoId: "dQw4w9WgXcQ", family: .web, context: context, visitorData: nil, locale: locale)

        #expect(request.headers["User-Agent"] == nil)
        #expect(request.headers.keys.contains("User-Agent") == false)
        #expect(request.headers["X-Goog-Visitor-Id"] == nil)

        let client = try clientDict(from: request.body)
        #expect(client["clientName"] as? String == "WEB")
        for key in ["deviceMake", "deviceModel", "osName", "osVersion", "androidSdkVersion", "visitorData"] {
            #expect(client[key] == nil, "unexpected key \(key)")
        }
    }

    private func clientDict(from body: Data?) throws -> [String: Any] {
        let json = try JSONSerialization.jsonObject(with: try #require(body)) as? [String: Any]
        let context = try #require(json?["context"] as? [String: Any])
        return try #require(context["client"] as? [String: Any])
    }
}

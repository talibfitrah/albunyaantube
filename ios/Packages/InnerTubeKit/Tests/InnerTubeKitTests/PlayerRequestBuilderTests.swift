import Foundation
import Testing
@testable import InnerTubeKit

@Suite struct PlayerRequestBuilderTests {
    private let builder = PlayerRequestBuilder()
    private let locale = InnerTubeLocale(hl: "ar", gl: "MA")

    @Test func visionOSBuildCarriesVisitorDataInBodyAndHeaderAndInjectedLocale() throws {
        let context = RemoteConfig.bundledDefault.clients["visionos"]!
        let request = builder.build(
            videoId: "xc7keR2piUM", family: .visionos, context: context, visitorData: "VISITOR123", locale: locale)

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
            videoId: "xc7keR2piUM", family: .visionos, context: context, visitorData: "VISITOR123", locale: locale)
        let request2 = builder.build(
            videoId: "xc7keR2piUM", family: .visionos, context: context, visitorData: "VISITOR123", locale: locale)

        #expect(request1.body == request2.body)
    }

    @Test func webBuildOmitsDeviceFieldsAndUserAgentHeader() throws {
        let context = RemoteConfig.bundledDefault.clients["web"]!
        #expect(context.userAgent == nil)
        let request = builder.build(
            videoId: "xc7keR2piUM", family: .web, context: context, visitorData: nil, locale: locale)

        #expect(request.headers["User-Agent"] == nil)
        #expect(request.headers.keys.contains("User-Agent") == false)
        #expect(request.headers["X-Goog-Visitor-Id"] == nil)

        let client = try clientDict(from: request.body)
        #expect(client["clientName"] as? String == "WEB")
        for key in ["deviceMake", "deviceModel", "osName", "osVersion", "androidSdkVersion", "visitorData"] {
            #expect(client[key] == nil, "unexpected key \(key)")
        }
    }

    /// Spec R4: VISIONOS and ANDROID bodies must stay byte-identical. Pinned on the code BEFORE the TV change.
    @Test func theVisionOSBodyIsExactlyTodays() throws {
        let context = try #require(RemoteConfig.bundledDefault.clients["visionos"])
        let request = builder.build(videoId: "xc7keR2piUM", family: .visionos, context: context, visitorData: "VISITOR123", locale: locale)
        #expect(String(decoding: try #require(request.body), as: UTF8.self)
            == #"{"contentCheckOk":true,"context":{"client":{"clientName":"VISIONOS","clientVersion":"1.02","deviceMake":"Apple","deviceModel":"RealityDevice17,1","gl":"MA","hl":"ar","osName":"visionOS","osVersion":"26.5.23O471","visitorData":"VISITOR123"}},"racyCheckOk":true,"videoId":"xc7keR2piUM"}"#)
    }

    /// R4: the spike's measured TV request (`G0/SolverSpike.java` `tvSamsungPlayer`): www.youtube.com, Origin, the body
    /// userAgent and the playbackContext.
    @Test func theTVRequestCarriesTheSpikesFieldsAndHeaders() throws {
        let context = try #require(RemoteConfig.bundledDefault.clients["tvSamsung"])
        let request = builder.build(videoId: "Ix7uzPm3cU8", family: .tvSamsung, context: context, visitorData: "VISITOR123",
                                    locale: locale, signatureTimestamp: 20739)
        #expect(request.url.absoluteString == "https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
        #expect(request.headers["Origin"] == "https://www.youtube.com")
        #expect(request.headers["X-YouTube-Client-Name"] == "7")
        #expect(request.headers["X-YouTube-Client-Version"] == "5.20260707")
        #expect(request.headers["User-Agent"] == context.userAgent)
        #expect(request.headers["X-Goog-Visitor-Id"] == "VISITOR123")
        let client = try clientDict(from: request.body)
        #expect(client["clientName"] as? String == "TVHTML5")
        #expect(client["deviceMake"] as? String == "Samsung")
        #expect(client["deviceModel"] as? String == "UKS9800")
        #expect(client["osName"] as? String == "Tizen")
        #expect(client["osVersion"] as? String == "2.4.0")
        #expect(client["userAgent"] as? String == context.userAgent)
        #expect(client["hl"] as? String == "ar")
        let bodyData = try #require(request.body)   // not nested: #require inside #require is a recursive macro expansion
        let body = try #require(JSONSerialization.jsonObject(with: bodyData) as? [String: Any])
        let playback = try #require((body["playbackContext"] as? [String: Any])?["contentPlaybackContext"] as? [String: Any])
        #expect(playback["html5Preference"] as? String == "HTML5_PREF_WANTS")
        #expect(playback["signatureTimestamp"] as? Int == 20739)
        #expect(body["contentCheckOk"] as? Bool == true)
        #expect(body["racyCheckOk"] as? Bool == true)
    }

    /// R4: the TV gates are by family -- every context carries a userAgent, and a stray sts must change nothing.
    @Test(arguments: ["visionos", "android"])
    func theTVGatesNeverReachVisionOSOrAndroid(_ key: String) throws {
        let context = try #require(RemoteConfig.bundledDefault.clients[key])
        let family: ClientFamily = key == "visionos" ? .visionos : .android
        let plain = builder.build(videoId: "xc7keR2piUM", family: family, context: context, visitorData: "V", locale: locale)
        let withSTS = builder.build(videoId: "xc7keR2piUM", family: family, context: context, visitorData: "V", locale: locale,
                                    signatureTimestamp: 20739)
        #expect(plain.body == withSTS.body)
        #expect(withSTS.headers["Origin"] == nil)
        #expect(withSTS.url.host() == "youtubei.googleapis.com")
        let text = String(decoding: try #require(withSTS.body), as: UTF8.self)
        #expect(!text.contains("playbackContext"))
        #expect(!text.contains("userAgent"))
    }

    private func clientDict(from body: Data?) throws -> [String: Any] {
        let json = try JSONSerialization.jsonObject(with: try #require(body)) as? [String: Any]
        let context = try #require(json?["context"] as? [String: Any])
        return try #require(context["client"] as? [String: Any])
    }
}

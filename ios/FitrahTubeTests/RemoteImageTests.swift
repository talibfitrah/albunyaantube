import Foundation
import Testing
import UIKit
@testable import FitrahTube

/// Owner issue 3: a picture that is not coming is no picture -- a refused status, or YouTube's own
/// grey "no thumbnail" image. The glyph `RemoteImage` then draws is view code (screenshots cover it).
@Suite(.perTest)
struct RemoteImageTests {
    private static func response(_ status: Int, _ mime: String) -> HTTPURLResponse {
        HTTPURLResponse(url: URL(string: "https://i.ytimg.com/vi/lesson00001/hqdefault.jpg")!, statusCode: status,
                        httpVersion: "HTTP/1.1", headerFields: ["Content-Type": mime])!
    }

    @Test func aNon2xxResponseIsNoPictureEvenWithAnImageBody() {
        // i.ytimg answers a missing /vi/ thumbnail with a grey JPEG and a 404.
        #expect(!RemoteImage.isImage(Self.response(404, "image/jpeg")))
        #expect(!RemoteImage.isImage(Self.response(403, "image/jpeg")))
        #expect(!RemoteImage.isImage(Self.response(200, "text/html")))
        #expect(RemoteImage.isImage(Self.response(200, "image/jpeg")))
    }

    @Test func youTubesNoThumbnailImageIsNoPicture() async {
        let url = URL(string: "https://i.ytimg.com/img/no_thumbnail.jpg")!
        let image = UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).image { _ in }
        RemoteImage.cache.setObject(image, forKey: url as NSURL) // the cache is a shared static
        defer { RemoteImage.cache.removeObject(forKey: url as NSURL) }
        // nil although cached: the rule runs before the cache and the network.
        #expect(await RemoteImage.cachedImage(for: url) == nil)
    }
}

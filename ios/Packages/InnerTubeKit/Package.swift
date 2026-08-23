// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "InnerTubeKit",
    platforms: [.iOS(.v18), .macOS(.v14)],
    products: [
        // Dynamic: the app and its hosted test target both link this package; a static product is linked twice.
        .library(name: "InnerTubeKit", type: .dynamic, targets: ["InnerTubeKit"]),
    ],
    targets: [
        .target(name: "InnerTubeKit", resources: [.copy("Resources")]),
        .testTarget(
            name: "InnerTubeKitTests",
            dependencies: ["InnerTubeKit"],
            resources: [.copy("Fixtures")]
        ),
    ]
)

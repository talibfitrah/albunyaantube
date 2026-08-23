// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "FitrahAPI",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "FitrahAPI", targets: ["FitrahAPI"])],
    targets: [
        .target(name: "FitrahAPI"),
        .testTarget(name: "FitrahAPITests", dependencies: ["FitrahAPI"]),
    ]
)

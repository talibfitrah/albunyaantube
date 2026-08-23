// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "FitrahAPI",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "FitrahAPI", type: .dynamic, targets: ["FitrahAPI"])],
    dependencies: [
        // Build-time only: used by `swift package plugin generate-code-from-openapi`.
        .package(url: "https://github.com/apple/swift-openapi-generator", from: "1.0.0"),
        .package(url: "https://github.com/apple/swift-openapi-runtime", from: "1.0.0"),
        .package(url: "https://github.com/apple/swift-openapi-urlsession", from: "1.0.0"),
        .package(url: "https://github.com/apple/swift-http-types", from: "1.0.0"),
    ],
    targets: [
        .target(
            name: "FitrahAPI",
            dependencies: [
                .product(name: "OpenAPIRuntime", package: "swift-openapi-runtime"),
                .product(name: "OpenAPIURLSession", package: "swift-openapi-urlsession"),
                .product(name: "HTTPTypes", package: "swift-http-types"),
            ]
        ),
        .testTarget(name: "FitrahAPITests", dependencies: ["FitrahAPI"]),
    ]
)

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
        // `.copy("Resources/remote-config-default.json")`, not `.copy("Resources")`: the latter
        // preserves the source folder name and lands the file at `<bundle>/Resources/…`, and
        // Xcode's simulator-SDK `codesign` treats a top-level folder literally named "Resources"
        // inside a flat (no `Contents/`) iOS bundle as an ambiguous/invalid bundle format --
        // "bundle format unrecognized, invalid, or unsuitable" -- failing the build the first time
        // this package is linked into an Xcode project (`swift test` never hits this signing
        // step, which is why InnerTubeKit's own suite didn't catch it). Copying the file directly
        // puts it at the bundle's top level, which is also what `Bundle.module.url(forResource:)`
        // (no `subdirectory:`) actually looks up.
        .target(name: "InnerTubeKit", resources: [.copy("Resources/remote-config-default.json")]),
        .testTarget(
            name: "InnerTubeKitTests",
            dependencies: ["InnerTubeKit"],
            resources: [.copy("Fixtures")]
        ),
    ]
)

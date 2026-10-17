// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "OpenBridge",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "OpenBridge", targets: ["OpenBridge"])],
    targets: [
        // Catches Objective-C exceptions from Apple's USB framework, which Swift cannot.
        .target(name: "ObjCSupport"),
        .target(name: "MTPCore", dependencies: [.target(name: "ObjCSupport", condition: .when(platforms: [.macOS]))]),
        .target(name: "BridgeCore", dependencies: ["MTPCore"]),
        .executableTarget(name: "OpenBridge", dependencies: ["BridgeCore", "MTPCore"]),
        // Test-only simulated phone; not linked into the app.
        .target(name: "MTPTestSupport", dependencies: ["MTPCore"]),
        .testTarget(name: "MTPCoreTests", dependencies: ["MTPCore", "MTPTestSupport"]),
        .testTarget(name: "BridgeCoreTests", dependencies: ["BridgeCore", "MTPCore", "MTPTestSupport"])
    ]
)

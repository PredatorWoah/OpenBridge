// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "OpenBridge",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "OpenBridge", targets: ["OpenBridge"])],
    targets: [
        .target(name: "BridgeCore"),
        .executableTarget(name: "OpenBridge", dependencies: ["BridgeCore"]),
        .testTarget(name: "BridgeCoreTests", dependencies: ["BridgeCore"])
    ]
)

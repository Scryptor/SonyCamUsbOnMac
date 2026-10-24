// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ZVViewer",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "ZVCore", targets: ["ZVCore"]),
    ],
    targets: [
        .target(name: "ZVCore"),
        .target(name: "ZVCamera", dependencies: ["ZVCore"]),
        .executableTarget(name: "zv-probe", dependencies: ["ZVCore", "ZVCamera"]),
        .executableTarget(name: "ZVViewer", dependencies: ["ZVCore", "ZVCamera"]),
        .testTarget(name: "ZVCoreTests", dependencies: ["ZVCore"], resources: [.copy("Fixtures")]),
    ],
    swiftLanguageModes: [.v5]
)

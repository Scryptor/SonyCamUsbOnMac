// swift-tools-version: 6.0
// SPIKE (throwaway): feasibility probe for driving a Sony ZV-E10 over USB via ImageCaptureCore.
import PackageDescription

let package = Package(
    name: "ptp-probe",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "ptp-probe", path: "Sources/ptp-probe")
    ],
    swiftLanguageModes: [.v5]
)

#if canImport(IOUSBHost)
import Foundation

/// Finding OpenBridge on a phone plugged in with USB debugging: while sharing, the phone listens on its own loopback
/// address, on the first free port of 41987 to 41990 (see docs/protocol.md, Fast USB).
public enum FastUSB {
    public static let ports: [UInt16] = [41987, 41988, 41989, 41990]
    /// Starts sharing over the cable on the phone (the app opens and shares on loopback only).
    static let launch = "shell:am start -n org.openbridge.android/.MainActivity -a org.openbridge.android.SHARE_USB"

    /// OpenBridge answering on the phone: a tunnel to it, its certificate fingerprint and the phone's model.
    public struct Found {
        public let tunnel: ADBTunnel
        public let fingerprint: Data
        public let model: String?
    }

    /// Looks for OpenBridge on the phone's loopback ports and keeps the first whose certificate `known` accepts (a
    /// saved phone), or else the first that answers TLS at all. If nothing answers, starts sharing on the phone once
    /// and keeps looking for about ten seconds. Returns nil when nothing answers. `allowPrompt`: see `USBDebugging`.
    public static func find(allowPrompt: (() -> Void)?, known: @escaping (Data) -> Bool,
                            log: @escaping (String) -> Void) async throws -> Found? {
        let adb = try await FastUSB.background { try USBDebugging.shared.connection(allowPrompt: allowPrompt) }
        let model = adb.model
        var launchedAt: Date?
        while true {
            try Task.checkCancellation()
            let listening = try await FastUSB.background {
                FastUSB.ports.filter { port in
                    guard let stream = try? USBDebugging.shared.open("tcp:\(port)", timeout: 3) else { return false }
                    stream.close()
                    return true
                }
            }
            var unknown: Found?
            for port in listening {
                let tunnel = try ADBTunnel(remotePort: port) { try USBDebugging.shared.open($0, timeout: 5) }
                guard let fingerprint = try? await BridgeClient.fingerprint(tunnel: tunnel.localPort) else {
                    tunnel.stop(); continue
                }
                let found = Found(tunnel: tunnel, fingerprint: fingerprint, model: model)
                if known(fingerprint) { unknown?.tunnel.stop(); return found }
                if unknown == nil { unknown = found } else { tunnel.stop() }
            }
            if let unknown { return unknown }
            if let launchedAt {
                if Date().timeIntervalSince(launchedAt) > 12 { return nil }
            } else {
                log("Fast USB: nothing listening on the phone; starting sharing over the cable")
                launchedAt = Date()
                // Prints what `am` says; the app may not be installed, which shows as nothing listening afterwards.
                _ = try? await FastUSB.background { () throws -> Void in
                    let shell = try USBDebugging.shared.open(FastUSB.launch)
                    while shell.readAvailable() != nil {}
                    shell.close()
                }
            }
            try await Task.sleep(nanoseconds: 1_000_000_000)
        }
    }

    static func background<T>(_ work: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async { continuation.resume(with: Result { try work() }) }
        }
    }
}
#endif

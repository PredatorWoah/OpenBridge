import Foundation
import Network
import BridgeCore

/// Lists phones sharing with OpenBridge on this Wi‑Fi (DNS-SD `_openbridge._tcp`), so nobody types an address.
/// The phone announces itself only while sharing; pairing still needs the 6-digit code.
@MainActor
final class PhoneDiscovery: ObservableObject {
    struct Phone: Identifiable, Equatable {
        let name: String, host: String, port: String
        /// The phone's short ID (from its certificate), to recognize a saved phone at a new address.
        var phoneID: String?
        var id: String { "\(host):\(port)" }
    }

    @Published private(set) var phones: [Phone] = []
    private var browser: NWBrowser?
    /// Phones announced on the network, and the phone found at the gateway (its own hotspot, which isn't announced).
    private var announced: [Phone] = []
    private(set) var atGateway: Phone?

    func start() {
        guard browser == nil else { return }
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: "_openbridge._tcp", domain: nil), using: .tcp)
        let owner = WeakOwner(self)
        browser.browseResultsChangedHandler = { results, _ in
            let found = results.compactMap { result -> Phone? in
                guard case .service(let name, _, _, _) = result.endpoint, case .bonjour(let txt) = result.metadata,
                      let host = txt["ip"], let port = txt["port"] else { return nil }
                return Phone(name: name, host: host, port: port, phoneID: txt["id"])
            }
            Task { @MainActor in owner.discovery?.announce(found.sorted { $0.name < $1.name }) }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    /// Phones to show in the README screenshots (`--render-screenshots`), without browsing the network.
    func showSample(_ phones: [Phone]) { self.phones = phones }

    private func announce(_ found: [Phone]) {
        if found.map(\.id) != announced.map(\.id) {
            Diagnostics.log(found.isEmpty ? "No phones announced on this network" : "Phones announced: " + found.map { "\($0.name) at \($0.id)" }.joined(separator: ", "))
        }
        announced = found; merge()
    }

    /// The phone answering at the gateway, or nil when nothing does.
    func found(atGateway phone: Phone?) {
        guard phone != atGateway else { return }
        atGateway = phone; merge()
    }

    private func merge() {
        var all = announced
        if let atGateway, !all.contains(where: { $0.host == atGateway.host || ($0.phoneID != nil && $0.phoneID == atGateway.phoneID) }) {
            all.append(atGateway)
        }
        phones = all
    }

    func stop() {
        browser?.cancel(); browser = nil; announced = []; atGateway = nil; phones = []
    }
}

/// The network this Mac is on.
enum LocalNetwork {
    /// The default gateway, when it's a private IPv4 address. On a phone's hotspot the phone is the gateway, and
    /// OpenBridge listens there (port 41987), so it's one more address to try for a saved phone.
    static func gateway() async -> String? {
        await withCheckedContinuation { (continuation: CheckedContinuation<String?, Never>) in
            DispatchQueue.global(qos: .utility).async {
                let process = Process()
                process.executableURL = URL(fileURLWithPath: "/sbin/route")
                process.arguments = ["-n", "get", "default"]
                let output = Pipe()
                process.standardOutput = output
                process.standardError = FileHandle.nullDevice
                do { try process.run() } catch { continuation.resume(returning: nil); return }
                let text = String(decoding: output.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
                process.waitUntilExit()
                continuation.resume(returning: LocalNetwork.parseGateway(text))
            }
        }
    }

    /// "    gateway: 192.168.43.1" from `route -n get default`.
    static func parseGateway(_ output: String) -> String? {
        for line in output.split(separator: "\n") {
            let parts = line.split(separator: ":", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            if parts.count == 2, parts[0] == "gateway", Wire.privateIPv4(parts[1]) { return parts[1] }
        }
        return nil
    }
}

/// Lets the browser's callback reach the discovery object without keeping it alive.
private final class WeakOwner: @unchecked Sendable {
    weak var discovery: PhoneDiscovery?
    init(_ discovery: PhoneDiscovery) { self.discovery = discovery }
}

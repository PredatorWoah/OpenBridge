import Foundation
import Network

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
            Task { @MainActor in owner.discovery?.phones = found.sorted { $0.name < $1.name } }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    func stop() {
        browser?.cancel(); browser = nil; phones = []
    }
}

/// Lets the browser's callback reach the discovery object without keeping it alive.
private final class WeakOwner: @unchecked Sendable {
    weak var discovery: PhoneDiscovery?
    init(_ discovery: PhoneDiscovery) { self.discovery = discovery }
}

import Foundation
import Network

/// Lists phones sharing with OpenBridge on this Wi‑Fi (DNS-SD `_openbridge._tcp`), so nobody types an address.
/// The phone announces itself only while sharing; pairing still needs the 6-digit code.
@MainActor
final class PhoneDiscovery: ObservableObject {
    struct Phone: Identifiable, Equatable {
        let name: String, host: String, port: String
        var id: String { "\(host):\(port)" }
    }

    @Published private(set) var phones: [Phone] = []
    private var browser: NWBrowser?

    func start() {
        guard browser == nil else { return }
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: "_openbridge._tcp", domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            let found = results.compactMap { result -> Phone? in
                guard case .service(let name, _, _, _) = result.endpoint, case .bonjour(let txt) = result.metadata,
                      let host = txt["ip"], let port = txt["port"] else { return nil }
                return Phone(name: name, host: host, port: port)
            }
            Task { @MainActor in self?.phones = found.sorted { $0.name < $1.name } }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    func stop() {
        browser?.cancel(); browser = nil; phones = []
    }
}

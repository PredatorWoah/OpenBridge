import Foundation
import BridgeCore

/// A phone this Mac paired with. It reconnects without a code until it's forgotten here or on the phone.
struct SavedPhone: Codable, Identifiable, Equatable {
    var name: String
    var host: String
    var port: UInt16
    let fingerprint: Data
    let secret: Data
    /// The short ID the phone announces on the network: the first 8 bytes of its certificate fingerprint.
    var id: String { fingerprint.prefix(8).map { String(format: "%02x", $0) }.joined() }

    func client() throws -> BridgeClient { try BridgeClient(saved: host, port: port, fingerprint: fingerprint, secret: secret) }
}

/// Saved phones, in ~/Library/Application Support/OpenBridge/phones.json: readable only by this user (mode 0600)
/// and left out of Time Machine backups. Not in the Keychain: with the app's ad-hoc signature, macOS would ask for
/// the password after every update. (The screen mirroring key is kept the same way.)
enum SavedPhones {
    private static var file: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("OpenBridge", isDirectory: true).appendingPathComponent("phones.json")
    }

    static func load() -> [SavedPhone] {
        guard let data = try? Data(contentsOf: file), let phones = try? JSONDecoder().decode([SavedPhone].self, from: data) else { return [] }
        return phones.filter { $0.fingerprint.count == 32 && $0.secret.count == 32 }
    }

    static func save(_ phones: [SavedPhone]) {
        do {
            let folder = file.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(phones)
            let temporary = folder.appendingPathComponent("phones.tmp")
            FileManager.default.createFile(atPath: temporary.path, contents: data, attributes: [.posixPermissions: 0o600])
            if FileManager.default.fileExists(atPath: file.path) { _ = try FileManager.default.replaceItemAt(file, withItemAt: temporary) }
            else { try FileManager.default.moveItem(at: temporary, to: file) }
            var location = file
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? location.setResourceValues(values)
        } catch {}
    }
}

/// A newer OpenBridge release, to offer.
struct AvailableUpdate: Equatable {
    let version: String
    let page: URL
}

/// One anonymous request to GitHub per launch for the latest release; nothing about the user is sent, and any
/// failure (offline, blocked, rate limited) is silent.
enum Updates {
    static let releases = URL(string: "https://github.com/PredatorWoah/OpenBridge/releases/latest")!

    static func newer(than current: String) async -> AvailableUpdate? {
        var request = URLRequest(url: URL(string: "https://api.github.com/repos/PredatorWoah/OpenBridge/releases/latest")!, timeoutInterval: 5)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let tag = json["tag_name"] as? String else { return nil }
        let version = tag.hasPrefix("v") ? String(tag.dropFirst()) : tag
        return isNewer(version, than: current) ? AvailableUpdate(version: version, page: releases) : nil
    }

    /// Compares dotted versions as numbers, so 0.9.10 is newer than 0.9.9.
    static func isNewer(_ candidate: String, than current: String) -> Bool {
        let a = candidate.split(separator: ".").map { Int($0) ?? 0 }, b = current.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(a.count, b.count) {
            let x = i < a.count ? a[i] : 0, y = i < b.count ? b[i] : 0
            if x != y { return x > y }
        }
        return false
    }
}

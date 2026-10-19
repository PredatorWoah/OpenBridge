import Foundation
import AppKit
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

/// Where photos are imported to, and which ones were imported from each phone
/// (~/Library/Application Support/OpenBridge/imported-<phone>.txt).
enum Imports {
    private static var base: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("OpenBridge", isDirectory: true)
    }

    /// The import folder; asks the first time, or when `choosing`.
    @MainActor static func folder(choosing: Bool) -> URL? {
        if !choosing, let path = UserDefaults.standard.string(forKey: "importFolder") {
            var directory: ObjCBool = false
            if FileManager.default.fileExists(atPath: path, isDirectory: &directory), directory.boolValue { return URL(fileURLWithPath: path, isDirectory: true) }
        }
        let panel = NSOpenPanel()
        panel.canChooseFiles = false; panel.canChooseDirectories = true; panel.canCreateDirectories = true
        panel.directoryURL = FileManager.default.urls(for: .picturesDirectory, in: .userDomainMask).first
        panel.prompt = "Import Here"
        panel.message = "Choose where to import new photos and videos. OpenBridge remembers what it imported from each phone."
        guard panel.runModal() == .OK, let folder = panel.url else { return nil }
        UserDefaults.standard.set(folder.path, forKey: "importFolder")
        return folder
    }

    static func line(_ entry: Entry) -> String { "\(entry.name)\t\(entry.size)" }

    /// Already copied there, with the same size.
    static func present(_ entry: Entry, in folder: URL) -> Bool {
        let path = folder.appendingPathComponent(Wire.fitted(entry.name, limit: 220)).path   // as imported (BrowserModel.macName)
        return ((try? FileManager.default.attributesOfItem(atPath: path)[.size]) as? NSNumber)?.int64Value == entry.size
    }

    private static func file(_ phone: String) -> URL {
        let safe = phone.map { $0.isLetter || $0.isNumber ? String($0) : "-" }.joined()
        return base.appendingPathComponent("imported-\(safe).txt")
    }

    static func load(_ phone: String) -> Set<String> {
        Set((try? String(contentsOf: file(phone), encoding: .utf8))?.split(separator: "\n").map(String.init) ?? [])
    }

    static func save(_ phone: String, _ lines: Set<String>) {
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        try? lines.sorted().joined(separator: "\n").write(to: file(phone), atomically: true, encoding: .utf8)
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

import Foundation

/// A download in progress, kept beside its destination so an interrupted download can continue later.
/// The name records the expected size, so a partial file is only ever continued for a file of that size.
struct PartialFile {
    let url: URL
    let handle: FileHandle
    /// Bytes already on disk from an earlier attempt.
    let offset: Int64

    static func location(for destination: URL, size: Int64) -> URL {
        // A name near the Mac's 255-byte limit is shortened here, so its partial file can exist too.
        let suffix = ".\(size).openbridge-part"
        let name = Wire.fitted(destination.lastPathComponent, limit: 254 - suffix.utf8.count)
        return destination.deletingLastPathComponent().appendingPathComponent("." + name + suffix)
    }

    /// Whether an earlier, interrupted download of this size can be continued at `destination`.
    static func resumable(at destination: URL, size: Int64) -> Bool {
        let url = location(for: destination, size: size)
        let existing = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
        return existing > 0 && existing < size
    }

    static func open(for destination: URL, size: Int64) throws -> PartialFile {
        let manager = FileManager.default
        // Fail before transferring anything; the final move would refuse to replace it anyway.
        guard !manager.fileExists(atPath: destination.path) else {
            throw BridgeError.message("\(destination.lastPathComponent) already exists. Choose a new name; OpenBridge never replaces files.")
        }
        let url = location(for: destination, size: size)
        var offset: Int64 = 0
        if let existing = (try? manager.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value {
            if existing > 0 && existing < size { offset = existing } else { try? manager.removeItem(at: url) }
        }
        if offset == 0 {
            guard manager.createFile(atPath: url.path, contents: nil, attributes: [.posixPermissions: 0o600]) else {
                throw BridgeError.message("Cannot create the download in the selected folder.")
            }
        }
        let handle = try FileHandle(forWritingTo: url)
        do { try handle.seek(toOffset: UInt64(offset)) } catch { try? handle.close(); throw error }
        return PartialFile(url: url, handle: handle, offset: offset)
    }

    func write(_ data: Data) throws { try handle.write(contentsOf: data) }

    /// Publishes the finished file. FileManager.moveItem fails if the destination exists, so nothing is replaced.
    func finish(at destination: URL) throws {
        try handle.synchronize()
        try handle.close()
        try FileManager.default.moveItem(at: url, to: destination)
    }

    /// Ends this attempt. A cancelled or unusable download is deleted; an interrupted one is kept to resume.
    func end(after error: Error) {
        try? handle.synchronize()
        try? handle.close()
        if error is CancellationError || (error as? BridgeError)?.discardsPartial == true {
            try? FileManager.default.removeItem(at: url)
        }
    }
}

extension BridgeError {
    /// The phone's file changed or can't be continued, so the partial copy is useless.
    static let fileChanged = BridgeError.message("The file on the phone changed since the download started. Download it again.")
    var discardsPartial: Bool {
        if case .message(let text) = self { return text == BridgeError.fileChanged.errorDescription }
        return false
    }
}

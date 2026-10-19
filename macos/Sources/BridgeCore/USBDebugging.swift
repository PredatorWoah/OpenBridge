#if canImport(IOUSBHost)
import Foundation
import MTPCore

/// The one USB debugging connection to the plugged-in phone, shared by Fast USB and screen mirroring: macOS lets only
/// one client open the phone's debugging interface at a time, so they take turns on the same connection instead.
/// While it's open, other tools (adb, Android Studio) can't use the phone; it closes on unplug or disconnect.
public final class USBDebugging: @unchecked Sendable {
    public static let shared = USBDebugging()

    private let lock = NSLock()
    /// One handshake at a time; later callers wait and then use the connection it opened.
    private let opening = NSLock()
    private var current: ADBConnection?

    /// The name of a phone plugged in with USB debugging on, if any. Cheap enough to poll.
    public static func attachedPhone() -> String? { USBTransport.attachedDebugging() }

    /// The key name the phone shows in its "Allow USB debugging?" prompt.
    static var keyName: String { "OpenBridge@" + (Host.current().localizedName ?? "Mac").replacingOccurrences(of: " ", with: "-") }

    /// The shared connection, opened if needed. Blocking: call it from a background thread. Without `allowPrompt`, a
    /// phone that hasn't allowed this Mac yet fails with `ADBError.notAllowed` instead of asking its user; with it,
    /// `allowPrompt` runs when the phone asks, and this waits up to two minutes for an answer.
    public func connection(allowPrompt: (() -> Void)? = nil) throws -> ADBConnection {
        if let live = alive() { return live }
        opening.lock(); defer { opening.unlock() }
        if let live = alive() { return live }
        let transport = try USBTransport.openADB()
        // A phone that doesn't answer fails soon (or after its user's two minutes); once open, streams may idle.
        transport.readTimeout = allowPrompt == nil ? 8 : 125
        let adb = try ADBConnection.connect(transport: transport, key: try ADBKey.loadOrCreate(), name: USBDebugging.keyName,
                                            offerKey: allowPrompt != nil, ready: { transport.readTimeout = 0 },
                                            allowPrompt: allowPrompt ?? {})
        lock.lock(); current = adb; lock.unlock()
        return adb
    }

    /// Opens a service on the phone over the shared connection, reconnecting quietly (never prompting) if it dropped,
    /// for example after the phone restarted USB debugging.
    public func open(_ service: String, timeout: TimeInterval = 10) throws -> ADBStream {
        try connection().open(service, timeout: timeout)
    }

    /// Whether the shared connection is open now.
    public var isOpen: Bool { alive() != nil }

    /// Closes the shared connection (the phone was unplugged, or nothing needs it any more).
    public func disconnect() {
        lock.lock(); let adb = current; current = nil; lock.unlock()
        adb?.close()
    }

    private func alive() -> ADBConnection? {
        lock.lock(); defer { lock.unlock() }
        if let adb = current, !adb.isClosed { return adb }
        current = nil
        return nil
    }
}
#endif

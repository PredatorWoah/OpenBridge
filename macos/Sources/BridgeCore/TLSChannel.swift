import Foundation
import Network
import Security
import CryptoKit

/// Leaf-certificate pinning. Without a pin (pairing only), any certificate is accepted and its fingerprint is
/// recorded; the pairing exchange then proves it belongs to the phone before anything secret is trusted.
final class TLSChannel {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "org.openbridge.connection")
    private final class Observed { var fingerprint: Data? }
    private let observed: Observed
    /// SHA-256 of the server's leaf certificate, recorded during the handshake.
    var peerFingerprint: Data? { observed.fingerprint }

    init(host: String, port: UInt16, fingerprint: Data?) {
        let observed = Observed()
        self.observed = observed
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, complete in
            let reference = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let certificate = SecTrustGetCertificateAtIndex(reference, 0) else {
                complete(false); return
            }
            let seen = Data(SHA256.hash(data: SecCertificateCopyData(certificate) as Data))
            observed.fingerprint = seen
            complete(fingerprint.map { $0 == seen } ?? true)
        }, queue)
        connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!,
            using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()))
    }

    func close() { connection.cancel() }

    // Each operation has an inactivity timeout (30 seconds by default), including TLS negotiation.
    private func perform<T>(timeout seconds: TimeInterval = 30,
                            _ begin: @escaping (@escaping (Result<T, Error>) -> Void) -> Void) async throws -> T {
        try Task.checkCancellation()
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                queue.async {
                    var finished = false
                    let timeout = DispatchWorkItem {
                        if !finished {
                            finished = true
                            self.connection.cancel()
                            continuation.resume(throwing: BridgeError.message("Connection timed out. Check Wi-Fi and restart sharing if needed."))
                        }
                    }
                    self.queue.asyncAfter(deadline: .now() + seconds, execute: timeout)
                    begin { result in
                        guard !finished else { return }
                        finished = true
                        timeout.cancel()
                        continuation.resume(with: result)
                    }
                }
            }
        }, onCancel: { self.close() })
    }

    func connect() async throws {
        try await perform { (finish: @escaping (Result<Void, Error>) -> Void) in
            self.connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    self.connection.stateUpdateHandler = nil
                    finish(.success(()))
                case .failed(let error): finish(.failure(error))
                case .cancelled: finish(.failure(CancellationError()))
                default: break
                }
            }
            self.connection.start(queue: self.queue)
        }
    }

    func send(_ data: Data) async throws {
        try await perform { (finish: @escaping (Result<Void, Error>) -> Void) in
            self.connection.send(content: data, completion: .contentProcessed { error in
                if let error { finish(.failure(error)) } else { finish(.success(())) }
            })
        }
    }

    func read(_ count: Int, timeout: TimeInterval = 30) async throws -> Data {
        if count == 0 { return Data() }
        var result = Data()
        while result.count < count {
            let remaining = count - result.count
            let chunk: Data = try await perform(timeout: timeout) { finish in
                self.connection.receive(minimumIncompleteLength: 1, maximumLength: remaining) { data, _, complete, error in
                    if let error { finish(.failure(error)) }
                    else if let data, !data.isEmpty { finish(.success(data)) }
                    else if complete { finish(.failure(BridgeError.message("Phone disconnected before the operation completed."))) }
                    else { finish(.failure(BridgeError.message("Empty network response."))) }
                }
            }
            result.append(chunk)
        }
        return result
    }

    func number(_ count: Int, timeout: TimeInterval = 30) async throws -> UInt64 { Wire.number(try await read(count, timeout: timeout)) }
    func string(limit: Int) async throws -> String {
        let size = try await number(4)
        guard size <= limit else { throw BridgeError.message("Invalid response string length.") }
        guard let text = String(data: try await read(Int(size)), encoding: .utf8) else {
            throw BridgeError.message("Invalid UTF-8 response.")
        }
        return text
    }
    func status(timeout: TimeInterval = 30) async throws {
        let code = try await number(1, timeout: timeout)
        if code == 1 { throw BridgeError.message(try await string(limit: 512)) }
        guard code == 0 else { throw BridgeError.message("Invalid response status.") }
    }
}

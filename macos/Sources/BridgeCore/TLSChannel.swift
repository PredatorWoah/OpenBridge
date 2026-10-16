import Foundation
import Network
import Security
import CryptoKit

/// Strict out-of-band leaf-certificate pinning. No trust-all or plaintext fallback.
final class TLSChannel {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "org.openbridge.connection")

    init(host: String, port: UInt16, fingerprint: Data) {
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, complete in
            let reference = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let certificate = SecTrustGetCertificateAtIndex(reference, 0) else {
                complete(false); return
            }
            let der = SecCertificateCopyData(certificate) as Data
            complete(Data(SHA256.hash(data: der)) == fingerprint)
        }, queue)
        connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!,
            using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()))
    }

    func close() { connection.cancel() }

    // Each operation has a 30-second inactivity timeout, including TLS negotiation.
    private func perform<T>(_ begin: @escaping (@escaping (Result<T, Error>) -> Void) -> Void) async throws -> T {
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
                    self.queue.asyncAfter(deadline: .now() + 30, execute: timeout)
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

    func read(_ count: Int) async throws -> Data {
        if count == 0 { return Data() }
        var result = Data()
        while result.count < count {
            let remaining = count - result.count
            let chunk: Data = try await perform { finish in
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

    func number(_ count: Int) async throws -> UInt64 { Wire.number(try await read(count)) }
    func string(limit: Int) async throws -> String {
        let size = try await number(4)
        guard size <= limit else { throw BridgeError.message("Invalid response string length.") }
        guard let text = String(data: try await read(Int(size)), encoding: .utf8) else {
            throw BridgeError.message("Invalid UTF-8 response.")
        }
        return text
    }
    func status() async throws {
        let code = try await number(1)
        if code == 1 { throw BridgeError.message(try await string(limit: 512)) }
        guard code == 0 else { throw BridgeError.message("Invalid response status.") }
    }
}

#if canImport(IOUSBHost)
import Foundation
import IOKit
import IOUSBHost
import ObjCSupport

/// Bulk pipes of a phone's MTP interface, opened directly with IOUSBHost. No driver or library is installed.
public final class USBTransport: MTPTransport {
    public let maxPacketSize: Int
    private let interface: IOUSBHostInterface
    private let input: IOUSBHostPipe
    private let output: IOUSBHostPipe
    private var closed = false
    private static let timeout: TimeInterval = 30
    /// Seconds a read may wait for the phone; 0 waits indefinitely (ADB streams can be idle for long).
    public var readTimeout: TimeInterval = USBTransport.timeout
    private static let exclusiveAccess: UInt32 = 0xE000_02C5

    private init(service: io_service_t) throws {
        interface = try guarded { try IOUSBHostInterface(__ioService: service, options: [], queue: nil, interestHandler: nil) }
        var bulkIn: UInt8?, bulkOut: UInt8?, packet = 512
        let configuration = interface.configurationDescriptor
        let descriptor = interface.interfaceDescriptor
        var endpoint = IOUSBGetNextEndpointDescriptor(configuration, descriptor, nil)
        while let current = endpoint {
            let address = current.pointee.bEndpointAddress
            if current.pointee.bmAttributes & 0x03 == 0x02 {            // bulk
                if address & 0x80 != 0 { bulkIn = address } else { bulkOut = address }
                packet = Int(UInt16(littleEndian: current.pointee.wMaxPacketSize) & 0x7FF)
            }
            let header = UnsafeRawPointer(current).assumingMemoryBound(to: IOUSBDescriptorHeader.self)
            endpoint = IOUSBGetNextEndpointDescriptor(configuration, descriptor, header)
        }
        guard let bulkIn, let bulkOut, packet > 0 else {
            interface.destroy()
            throw MTPError.transport("This USB device has no MTP data endpoints.")
        }
        maxPacketSize = packet
        let interface = interface
        do {
            input = try guarded { try interface.copyPipe(withAddress: Int(bulkIn)) }
            output = try guarded { try interface.copyPipe(withAddress: Int(bulkOut)) }
        } catch {
            _ = try? guarded { interface.destroy() }
            throw error
        }
    }

    deinit { close() }

    // Plain memory buffers: IOUSBHost's own ioData buffers are only an optimization, and resizing one
    // raises an Objective-C exception on some Macs.
    public func write(_ data: Data) throws {
        let buffer = NSMutableData(data: data)   // empty for a zero-length packet
        var transferred = 0
        try guarded { try output.__sendIORequest(with: buffer, bytesTransferred: &transferred, completionTimeout: USBTransport.timeout) }
        guard transferred == data.count else { throw MTPError.transport("The phone stopped receiving data. Unplug it and try again.") }
    }

    public func read(maximum: Int) throws -> Data {
        guard let buffer = NSMutableData(length: maximum) else { throw MTPError.transport("Not enough memory to read from the phone.") }
        var transferred = 0
        try guarded { try input.__sendIORequest(with: buffer, bytesTransferred: &transferred, completionTimeout: readTimeout) }
        return Data(bytes: buffer.bytes, count: min(transferred, maximum))
    }

    /// PTP class "Device Reset" request: abandons a half-finished transfer on the phone.
    public func abort() {
        let request = IOUSBDeviceRequest(bmRequestType: 0x21, bRequest: 0x66, wValue: 0,
                                         wIndex: UInt16(interface.interfaceDescriptor.pointee.bInterfaceNumber), wLength: 0)
        _ = try? guarded { try interface.__send(request, data: nil, bytesTransferred: nil, completionTimeout: 5) }
        _ = try? guarded { try input.clearStall() }
        _ = try? guarded { try output.clearStall() }
    }

    public func close() {
        guard !closed else { return }
        closed = true
        _ = try? guarded { interface.destroy() }
    }

    // MARK: Discovery

    /// Finds a USB-connected phone in File transfer (MTP) or PTP mode and opens a session.
    /// With `identity`, only that phone is accepted, so a reconnect never switches devices.
    public static func openSession(matching identity: MTPDeviceIdentity? = nil) throws -> (MTPSession, MTPDeviceInfo) {
        var busy = false, other = false, silent: Set<String> = [], seen: [String: Set<String>] = [:], reason: Error?
        for attempt in 0..<2 {
            let interfaces = usbInterfaces()
            defer { interfaces.forEach { IOObjectRelease($0.service) } }
            for item in interfaces { seen[item.device, default: []].insert(item.code.description) }
            // Android's MTP interface is vendor class ff/ff/00; PTP mode uses the still-image class 06/01/01.
            let candidates = interfaces.filter { $0.code.isMTP || $0.code.isPTP }.sorted { $0.code.isMTP && !$1.code.isMTP }
            for candidate in candidates {
                do {
                    let transport = try USBTransport(service: candidate.service)
                    let session = MTPSession(transport: transport)
                    do {
                        let info = try session.deviceInfo()
                        if let identity, info.identity != identity { other = true; transport.close(); continue }
                        try session.open()
                        return (session, info)
                    } catch { transport.close(); silent.insert(candidate.device); reason = error }
                } catch let error as NSError where UInt32(truncatingIfNeeded: error.code) == exclusiveAccess {
                    busy = true
                } catch { silent.insert(candidate.device); reason = error }
            }
            // macOS's photo import service claims phones for Photos and Image Capture. It restarts on demand,
            // so stopping it only lets this app open the phone; nothing is lost.
            guard busy, attempt == 0 else { break }
            releaseFromImageCapture()
        }
        if identity != nil && other && !busy {
            throw MTPError.transport("The phone you were using is no longer connected (a different one is). Disconnect and connect again.")
        }
        if busy {
            throw MTPError.transport("Another app (Photos, Image Capture or a file transfer app) is using the phone. Quit it and try again.")
        }
        if !silent.isEmpty {
            throw MTPError.transport("\(silent.sorted().joined(separator: ", ")) is connected but did not answer. Unlock the phone, choose File transfer again in its USB notification, and retry."
                + (reason.map { " Details: \($0.localizedDescription)" } ?? ""))
        }
        if seen.values.contains(where: { $0.contains(InterfaceCode.tethering) }) {
            throw MTPError.transport("The phone is in USB tethering mode, which macOS does not support. Turn off USB tethering, then choose File transfer in the phone's USB notification.")
        }
        // Name what the Mac does see, so a report shows whether the phone is attached and in which USB mode.
        let summary = seen.keys.sorted().map { "\($0) [\(seen[$0]!.sorted().joined(separator: " "))]" }.joined(separator: "; ")
        throw MTPError.transport("No phone in File transfer mode found. Connect it with a data cable, unlock it, and choose File transfer in the USB notification."
            + (summary.isEmpty ? " The Mac sees no USB devices with interfaces." : " USB devices seen: \(summary)."))
    }

    /// Opens the phone's ADB (USB debugging) interface, used for screen mirroring.
    public static func openADB() throws -> USBTransport {
        let interfaces = usbInterfaces()
        defer { interfaces.forEach { IOObjectRelease($0.service) } }
        guard let adb = interfaces.first(where: { $0.code.isADB }) else {
            throw MTPError.transport("USB debugging is off on the phone. Turn it on in Settings › Developer options › USB debugging, then try again.")
        }
        do {
            let transport = try USBTransport(service: adb.service)
            transport.readTimeout = 0
            return transport
        } catch let error as NSError where UInt32(truncatingIfNeeded: error.code) == exclusiveAccess {
            throw MTPError.transport("Another app is using USB debugging on the phone (for example adb or Android Studio). Quit it, or run `adb kill-server`, then try again.")
        }
    }

    /// Name of a phone attached in File transfer (MTP) or PTP mode, without opening it. Cheap enough to poll.
    public static func attachedPhone() -> String? {
        let interfaces = usbInterfaces()
        defer { interfaces.forEach { IOObjectRelease($0.service) } }
        return interfaces.first { $0.code.isMTP || $0.code.isPTP }?.device
    }

    struct InterfaceCode: CustomStringConvertible {
        let interfaceClass: Int, subclass: Int, interfaceProtocol: Int
        var isMTP: Bool { interfaceClass == 0xFF && subclass == 0xFF && interfaceProtocol == 0x00 }
        var isPTP: Bool { interfaceClass == 0x06 && subclass == 0x01 && interfaceProtocol == 0x01 }
        /// Android's USB debugging interface.
        var isADB: Bool { interfaceClass == 0xFF && subclass == 0x42 && interfaceProtocol == 0x01 }
        /// Android's USB tethering (RNDIS) control interface, as listed in the "devices seen" summary.
        static let tethering = "e0/01/03"
        var description: String { String(format: "%02x/%02x/%02x", interfaceClass, subclass, interfaceProtocol) }
    }

    /// Every USB interface on the Mac with its class codes and device name. IOKit matching dictionaries cannot
    /// match the vendor-specific class (0xff) without a vendor ID, so the codes are compared here instead.
    private static func usbInterfaces() -> [(service: io_service_t, code: InterfaceCode, device: String)] {
        var iterator: io_iterator_t = 0
        guard IOServiceGetMatchingServices(kIOMainPortDefault, IOServiceMatching("IOUSBHostInterface"), &iterator) == KERN_SUCCESS else { return [] }
        defer { IOObjectRelease(iterator) }
        var result: [(service: io_service_t, code: InterfaceCode, device: String)] = []
        while case let service = IOIteratorNext(iterator), service != 0 {
            func number(_ key: String) -> Int? {
                (IORegistryEntryCreateCFProperty(service, key as CFString, kCFAllocatorDefault, 0)?.takeRetainedValue() as? NSNumber)?.intValue
            }
            guard let interfaceClass = number("bInterfaceClass"), let subclass = number("bInterfaceSubClass"),
                  let interfaceProtocol = number("bInterfaceProtocol") else { IOObjectRelease(service); continue }
            let name = IORegistryEntrySearchCFProperty(service, kIOServicePlane, "USB Product Name" as CFString, kCFAllocatorDefault,
                                                       IOOptionBits(kIORegistryIterateRecursively | kIORegistryIterateParents)) as? String
            result.append((service, InterfaceCode(interfaceClass: interfaceClass, subclass: subclass, interfaceProtocol: interfaceProtocol),
                           name ?? "USB device"))
        }
        return result
    }

    private static func releaseFromImageCapture() {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/killall")
        process.arguments = ["ptpcamerad"]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try? process.run()
        process.waitUntilExit()
        Thread.sleep(forTimeInterval: 0.5)
    }
}

/// Runs `body`, turning an Objective-C exception from IOUSBHost into a readable error instead of a crash.
private func guarded<T>(_ body: () throws -> T) throws -> T {
    var result: Result<T, Error>?
    var exception: NSError?
    let finished = OBCatchException({ result = Result { try body() } }, &exception)
    guard finished, let result else {
        throw MTPError.transport("macOS's USB framework failed (\(exception?.localizedDescription ?? "unknown error")). Unplug the phone and try again.")
    }
    return try result.get()
}
#endif

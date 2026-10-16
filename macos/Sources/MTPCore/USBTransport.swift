#if canImport(IOUSBHost)
import Foundation
import IOKit
import IOUSBHost

/// Bulk pipes of a phone's MTP interface, opened directly with IOUSBHost. No driver or library is installed.
public final class USBTransport: MTPTransport {
    public let maxPacketSize: Int
    private let interface: IOUSBHostInterface
    private let input: IOUSBHostPipe
    private let output: IOUSBHostPipe
    private var closed = false
    private static let timeout: TimeInterval = 30
    private static let exclusiveAccess: UInt32 = 0xE000_02C5

    private init(service: io_service_t) throws {
        interface = try IOUSBHostInterface(__ioService: service, options: [], queue: nil, interestHandler: nil)
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
        input = try interface.copyPipe(withAddress: Int(bulkIn))
        output = try interface.copyPipe(withAddress: Int(bulkOut))
    }

    deinit { close() }

    public func write(_ data: Data) throws {
        let buffer = try interface.ioData(withCapacity: data.count)
        buffer.length = data.count
        data.withUnsafeBytes { buffer.replaceBytes(in: NSRange(location: 0, length: data.count), withBytes: $0.baseAddress!) }
        var transferred = 0
        try output.__sendIORequest(with: buffer, bytesTransferred: &transferred, completionTimeout: USBTransport.timeout)
        guard transferred == data.count else { throw MTPError.transport("The phone stopped receiving data. Unplug it and try again.") }
    }

    public func read(maximum: Int) throws -> Data {
        let buffer = try interface.ioData(withCapacity: maximum)
        buffer.length = maximum
        var transferred = 0
        try input.__sendIORequest(with: buffer, bytesTransferred: &transferred, completionTimeout: USBTransport.timeout)
        return Data(bytes: buffer.bytes, count: transferred)
    }

    /// PTP class "Device Reset" request: abandons a half-finished transfer on the phone.
    public func abort() {
        let request = IOUSBDeviceRequest(bmRequestType: 0x21, bRequest: 0x66, wValue: 0,
                                         wIndex: UInt16(interface.interfaceDescriptor.pointee.bInterfaceNumber), wLength: 0)
        _ = try? interface.__send(request, data: nil, bytesTransferred: nil, completionTimeout: 5)
        _ = try? input.clearStall()
        _ = try? output.clearStall()
    }

    public func close() {
        guard !closed else { return }
        closed = true
        interface.destroy()
    }

    // MARK: Discovery

    /// Finds a USB-connected phone in File transfer (MTP) or PTP mode and opens a session.
    /// With `identity`, only that phone is accepted, so a reconnect never switches devices.
    public static func openSession(matching identity: MTPDeviceIdentity? = nil) throws -> (MTPSession, MTPDeviceInfo) {
        var busy = false, other = false
        for attempt in 0..<2 {
            // Android's MTP interface is vendor class 0xFF/0xFF/0; PTP mode uses the still-image class 6/1/1.
            for (interfaceClass, subclass, interfaceProtocol) in [(0xFF, 0xFF, 0x00), (0x06, 0x01, 0x01)] {
                for service in services(interfaceClass, subclass, interfaceProtocol) {
                    defer { IOObjectRelease(service) }
                    do {
                        let transport = try USBTransport(service: service)
                        let session = MTPSession(transport: transport)
                        do {
                            let info = try session.deviceInfo()
                            if let identity, info.identity != identity { other = true; transport.close(); continue }
                            try session.open()
                            return (session, info)
                        } catch { transport.close() }
                    } catch let error as NSError where UInt32(truncatingIfNeeded: error.code) == exclusiveAccess {
                        busy = true
                    } catch {}
                }
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
        throw MTPError.transport("No phone found. Connect it with a USB cable, unlock it, and choose File transfer in the USB notification.")
    }

    private static func services(_ interfaceClass: Int, _ subclass: Int, _ interfaceProtocol: Int) -> [io_service_t] {
        let matching = IOServiceMatching("IOUSBHostInterface") as NSMutableDictionary
        matching["bInterfaceClass"] = interfaceClass
        matching["bInterfaceSubClass"] = subclass
        matching["bInterfaceProtocol"] = interfaceProtocol
        var iterator: io_iterator_t = 0
        guard IOServiceGetMatchingServices(kIOMainPortDefault, matching, &iterator) == KERN_SUCCESS else { return [] }
        defer { IOObjectRelease(iterator) }
        var result: [io_service_t] = []
        while case let service = IOIteratorNext(iterator), service != 0 { result.append(service) }
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
#endif

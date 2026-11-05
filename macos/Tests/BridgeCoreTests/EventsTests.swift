import XCTest
@testable import BridgeCore

/// EVENTS (19) and the requests of operations 20 to 23, byte for byte as docs/protocol.md describes them.
final class EventsTests: XCTestCase {
    private func string(_ text: String) -> Data { Wire.string(text) }

    private func event(_ kind: UInt8, _ body: Data) -> Data { Data([kind]) + Wire.integer(Int32(body.count)) + body }

    private func reply(cursor: Int64, _ events: [Data]) -> Data {
        var data: Data = Wire.integer(cursor) + Wire.integer(Int32(events.count))
        for item in events { data += item }
        return data
    }

    private func notificationBody(key: String = "0|com.whatsapp|1|null|10123", flags: UInt8 = 3, code: String = "") -> Data {
        var body = Data()
        body += string(key)
        body += string("WhatsApp")
        body += string("com.whatsapp")
        body += string("Mum")
        body += string("Dinner at 8? Your code is 482913")
        body += Wire.integer(Int64(1_790_000_000_000))
        body += Data([flags])
        body += string(code)
        return body
    }

    func testEveryKindDecodes() throws {
        let data = reply(cursor: 42, [
            event(0, Data()),
            event(3, Data([87, 1])),
            event(1, notificationBody(code: "482913")),
            event(2, string("0|com.whatsapp|1|null|10123"))
        ])
        let batch = try EventCodec.batch(data)
        XCTAssertEqual(batch.cursor, 42)
        XCTAssertEqual(batch.events.count, 4)
        XCTAssertEqual(batch.events[0], .reset)
        XCTAssertEqual(batch.events[1], .battery(BatteryState(level: 87, charging: true)))
        guard case .notification(let note) = batch.events[2] else { return XCTFail("Expected a notification") }
        XCTAssertEqual(note.key, "0|com.whatsapp|1|null|10123")
        XCTAssertEqual(note.app, "WhatsApp")
        XCTAssertEqual(note.package, "com.whatsapp")
        XCTAssertEqual(note.title, "Mum")
        XCTAssertEqual(note.text, "Dinner at 8? Your code is 482913")
        XCTAssertEqual(note.time.timeIntervalSince1970, 1_790_000_000, accuracy: 0.001)
        XCTAssertTrue(note.canReply)
        XCTAssertTrue(note.canDismiss)
        XCTAssertEqual(note.code, "482913")
        XCTAssertEqual(batch.events[3], .removed("0|com.whatsapp|1|null|10123"))
    }

    func testFlagsAreSeparateBits() throws {
        for (flags, reply, dismiss) in [(UInt8(0), false, false), (1, true, false), (2, false, true), (0xff, true, true)] {
            guard case .notification(let note)? = try EventCodec.event(kind: 1, body: notificationBody(flags: flags)) else {
                return XCTFail("Expected a notification")
            }
            XCTAssertEqual(note.canReply, reply, "flags \(flags)")
            XCTAssertEqual(note.canDismiss, dismiss, "flags \(flags)")
        }
    }

    func testUnknownKindsAreSkippedByLength() throws {
        let future = Data(repeating: 0xAB, count: 300)
        let data = reply(cursor: 7, [event(9, future), event(3, Data([15, 0])), event(200, Data()), event(0, Data())])
        let batch = try EventCodec.batch(data)
        XCTAssertEqual(batch.events, [.battery(BatteryState(level: 15, charging: false)), .reset])
        XCTAssertNil(try EventCodec.event(kind: 5, body: Data([1, 2, 3])))
    }

    private func mediaBody(app: String = "Spotify", title: String = "Clair de lune", artist: String = "Claude Debussy",
                           duration: Int64 = 300_000, position: Int64 = 61_500, state: UInt8 = 1, actions: UInt8 = 0x0F) -> Data {
        var body = Data()
        body += string(app)
        body += string(title)
        body += string(artist)
        body += Wire.integer(duration)
        body += Wire.integer(position)
        body += Data([state, actions])
        return body
    }

    func testMediaDecodes() throws {
        let batch = try EventCodec.batch(reply(cursor: 9, [event(0, Data()), event(4, mediaBody())]))
        XCTAssertEqual(batch.events.count, 2)
        guard case .media(let media) = batch.events[1] else { return XCTFail("Expected media") }
        XCTAssertEqual(media.app, "Spotify")
        XCTAssertEqual(media.title, "Clair de lune")
        XCTAssertEqual(media.artist, "Claude Debussy")
        XCTAssertEqual(media.duration, 300_000)
        XCTAssertEqual(media.position, 61_500)
        XCTAssertEqual(media.playback, .playing)
        XCTAssertTrue(media.active)
        XCTAssertTrue(media.canPlayPause && media.canNext && media.canPrevious && media.canSeek)
    }

    func testMediaUnknownValuesAndActionBits() throws {
        guard case .media(let unknown)? = try EventCodec.event(kind: 4, body: mediaBody(duration: -1, position: -1, state: 0, actions: 0)) else {
            return XCTFail("Expected media")
        }
        XCTAssertNil(unknown.duration)
        XCTAssertNil(unknown.position)
        XCTAssertEqual(unknown.playback, .none)
        XCTAssertFalse(unknown.active)
        XCTAssertFalse(unknown.canPlayPause || unknown.canNext || unknown.canPrevious || unknown.canSeek)
        for (bits, expected) in [(UInt8(1), [true, false, false, false]), (2, [false, true, false, false]),
                                 (4, [false, false, true, false]), (8, [false, false, false, true]), (0xF0, [false, false, false, false])] {
            guard case .media(let media)? = try EventCodec.event(kind: 4, body: mediaBody(state: 2, actions: bits)) else {
                return XCTFail("Expected media")
            }
            XCTAssertEqual(media.playback, .paused)
            XCTAssertEqual([media.canPlayPause, media.canNext, media.canPrevious, media.canSeek], expected, "actions \(bits)")
        }
        // Fields added later at the end are ignored.
        XCTAssertNoThrow(try EventCodec.event(kind: 4, body: mediaBody() + Data([7, 7, 7])))
    }

    func testMediaLimits() {
        XCTAssertThrowsError(try EventCodec.event(kind: 4, body: mediaBody(state: 3)))
        XCTAssertThrowsError(try EventCodec.event(kind: 4, body: mediaBody(duration: -2)))
        XCTAssertThrowsError(try EventCodec.event(kind: 4, body: mediaBody(position: -5)))
        XCTAssertThrowsError(try EventCodec.event(kind: 4, body: mediaBody(app: String(repeating: "a", count: 129))))
        XCTAssertNoThrow(try EventCodec.event(kind: 4, body: mediaBody(app: String(repeating: "a", count: 128))))
        XCTAssertThrowsError(try EventCodec.event(kind: 4, body: mediaBody(title: String(repeating: "t", count: 513))))
        XCTAssertThrowsError(try EventCodec.event(kind: 4, body: mediaBody(artist: String(repeating: "r", count: 513))))
        XCTAssertNoThrow(try EventCodec.event(kind: 4, body: mediaBody(title: String(repeating: "t", count: 512),
                                                                       artist: String(repeating: "r", count: 512))))
        XCTAssertThrowsError(try EventCodec.event(kind: 4, body: mediaBody().dropLast()))
    }

    func testMediaPositionAdvancesLocally() throws {
        let playing = MediaState(app: "", title: "", artist: "", duration: 10_000, position: 4_000, playback: .playing,
                                 canPlayPause: true, canNext: true, canPrevious: true, canSeek: true)
        XCTAssertEqual(playing.position(after: 2.5), 6_500)
        XCTAssertEqual(playing.position(after: 60), 10_000)
        XCTAssertEqual(playing.position(after: -3), 4_000)
        let paused: MediaState = playing.with(position: 4_000, playback: .paused)
        XCTAssertEqual(paused.position(after: 30), 4_000)
        XCTAssertNil(playing.with(position: nil, playback: .playing).position(after: 1))
        let endless = MediaState(app: "", title: "", artist: "", duration: nil, position: 1_000, playback: .playing,
                                 canPlayPause: true, canNext: false, canPrevious: false, canSeek: false)
        XCTAssertEqual(endless.position(after: 100), 101_000)
    }

    func testMediaControlRequests() throws {
        XCTAssertEqual(try EventCodec.mediaRequest(.play), Data([1]))
        XCTAssertEqual(try EventCodec.mediaRequest(.pause), Data([2]))
        XCTAssertEqual(try EventCodec.mediaRequest(.playPause), Data([3]))
        XCTAssertEqual(try EventCodec.mediaRequest(.next), Data([4]))
        XCTAssertEqual(try EventCodec.mediaRequest(.previous), Data([5]))
        XCTAssertEqual(try EventCodec.mediaRequest(.seek(90_500)), Data([6, 0, 0, 0, 0, 0, 0x01, 0x61, 0x84]))
        XCTAssertThrowsError(try EventCodec.mediaRequest(.seek(-1)))
    }

    func testFieldsAddedLaterAtTheEndAreIgnored() throws {
        XCTAssertEqual(try EventCodec.event(kind: 3, body: Data([50, 0, 9, 9])), .battery(BatteryState(level: 50, charging: false)))
        XCTAssertEqual(try EventCodec.event(kind: 0, body: Data([1])), .reset)
    }

    func testEmptyReply() throws {
        XCTAssertEqual(try EventCodec.batch(reply(cursor: 0, [])), EventBatch(cursor: 0, events: []))
    }

    func testLimitsAreEnforced() {
        // More than 200 events.
        var many: Data = Wire.integer(Int64(1)) + Wire.integer(Int32(201))
        for _ in 0..<201 { many += event(0, Data()) }
        XCTAssertThrowsError(try EventCodec.batch(many))
        // A body longer than 65,536 bytes.
        let long: Data = Wire.integer(Int64(1)) + Wire.integer(Int32(1)) + Data([9]) + Wire.integer(Int32(65_537)) + Data(count: 65_537)
        XCTAssertThrowsError(try EventCodec.batch(long))
        // A negative body length or cursor.
        let negative: Data = Wire.integer(Int64(1)) + Wire.integer(Int32(1)) + Data([9]) + Wire.integer(Int32(-1))
        XCTAssertThrowsError(try EventCodec.batch(negative))
        XCTAssertThrowsError(try EventCodec.batch(reply(cursor: -5, [])))
        // A body shorter than its length says, and bytes after the last event.
        XCTAssertThrowsError(try EventCodec.batch(Wire.integer(Int64(1)) + Wire.integer(Int32(1)) + Data([3]) + Wire.integer(Int32(2)) + Data([1])))
        XCTAssertThrowsError(try EventCodec.batch(reply(cursor: 1, []) + Data([0])))
        // Battery out of range, string limits, empty keys and invalid UTF-8.
        XCTAssertThrowsError(try EventCodec.event(kind: 3, body: Data([101, 0])))
        XCTAssertThrowsError(try EventCodec.event(kind: 3, body: Data([50, 2])))
        XCTAssertThrowsError(try EventCodec.event(kind: 3, body: Data([50])))
        XCTAssertThrowsError(try EventCodec.event(kind: 1, body: notificationBody(key: String(repeating: "k", count: 257))))
        XCTAssertNoThrow(try EventCodec.event(kind: 1, body: notificationBody(key: String(repeating: "k", count: 256))))
        XCTAssertThrowsError(try EventCodec.event(kind: 1, body: notificationBody(key: "")))
        XCTAssertThrowsError(try EventCodec.event(kind: 1, body: notificationBody(code: String(repeating: "1", count: 17))))
        XCTAssertThrowsError(try EventCodec.event(kind: 2, body: string("")))
        XCTAssertThrowsError(try EventCodec.event(kind: 2, body: Wire.integer(Int32(2)) + Data([0xC3, 0x28])))
        XCTAssertThrowsError(try EventCodec.event(kind: 1, body: notificationBody().dropLast(3)))
    }

    func testRequests() throws {
        XCTAssertEqual(EventCodec.eventsRequest(cursor: 5, wait: 20_000), Wire.integer(Int64(5)) + Wire.integer(Int32(20_000)))
        XCTAssertEqual(EventCodec.eventsRequest(cursor: -1, wait: 99_999), Wire.integer(Int64(0)) + Wire.integer(Int32(25_000)))
        XCTAssertEqual(EventCodec.eventsRequest(cursor: 0, wait: -3), Data(count: 12))
        XCTAssertEqual(try EventCodec.actionRequest(.dismiss), Data([1]))
        XCTAssertEqual(try EventCodec.actionRequest(.reply("OK")), Data([2, 0, 0, 0, 2]) + Data("OK".utf8))
        XCTAssertThrowsError(try EventCodec.actionRequest(.reply("")))
        XCTAssertThrowsError(try EventCodec.actionRequest(.reply(String(repeating: "é", count: 2_049))))
        XCTAssertNoThrow(try EventCodec.actionRequest(.reply(String(repeating: "a", count: 4_096))))
        XCTAssertThrowsError(try EventCodec.validateKey(""))
        XCTAssertThrowsError(try EventCodec.validateKey(String(repeating: "k", count: 257)))
    }

    func testLinks() throws {
        XCTAssertEqual(try EventCodec.openURLRequest(" https://example.com/a?b=c \n"), Wire.string("https://example.com/a?b=c"))
        for link in ["https://example.com", "http://192.168.1.2:8080/x", "HTTPS://Example.com/path"] {
            XCTAssertTrue(EventCodec.isWebLink(link), link)
        }
        let tooLong = "https://example.com/" + String(repeating: "a", count: 2_048)
        for link in ["", "example.com", "ftp://example.com", "file:///etc/passwd", "javascript:alert(1)", "https://", "see https://example.com", tooLong] {
            XCTAssertFalse(EventCodec.isWebLink(link), link)
            XCTAssertThrowsError(try EventCodec.openURLRequest(link), link)
        }
    }

    func testPhoneNames() throws {
        XCTAssertEqual(Wire.phoneName("report.pdf"), "report.pdf")
        XCTAssertEqual(Wire.phoneName("a:b*c?d|e<f>g\"h.txt"), "a_b_c_d_e_f_g_h.txt")
        XCTAssertEqual(Wire.phoneName(".openbridge-part-x"), "openbridge-part-x")
        XCTAssertEqual(Wire.phoneName("line\nbreak.txt"), "line_break.txt")
        XCTAssertEqual(Wire.phoneName(".."), "File")
        let long = Wire.phoneName(String(repeating: "长", count: 120) + ".jpg")
        XCTAssertLessThanOrEqual(long.utf8.count, 255)
        XCTAssertTrue(long.hasSuffix(".jpg"))
        for name in ["a:b", "..", "x\u{7}y", String(repeating: "é", count: 200)] {
            XCTAssertNoThrow(try Wire.validateName(Wire.phoneName(name)), name)
        }
    }
}

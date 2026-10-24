import Foundation
@testable import ZVCore

extension SessionTiming {
    static let test = SessionTiming(settle: .milliseconds(1), confirmTimeout: .milliseconds(200), pollInterval: .milliseconds(10))
}

func readySession(_ cam: FakeCamera) async throws -> CameraSession {
    let session = CameraSession(transport: cam, timing: .test)
    try await session.start()
    try await session.waitUntilReady()
    return session
}

/// Фейковая камера: отвечает на PTP-операции как ZV-E10 и хранит состояние свойств.
final class FakeCamera: PTPTransport, @unchecked Sendable {
    struct Prop {
        var code: UInt16
        var type: UInt16
        var getSet: UInt8 = 1
        var enabled: UInt8 = 1
        var current: Int64
        var values: [Int64]
    }

    typealias Sent = (op: UInt16, params: [UInt32], outData: Data?)

    let events: AsyncStream<PTPEvent>
    private let eventContinuation: AsyncStream<PTPEvent>.Continuation
    private let lock = NSLock()
    private let deviceInfoData: Data
    private var props: [Prop]
    private var _sent: [Sent] = []
    private var _extInfoEmptyReplies = 0
    private var _applyWrites = true
    private var _liveViewReplies: [PTPReply] = []
    private var _disconnectOnNext: UInt16?

    init(props: [Prop], deviceInfo: Data? = nil) {
        self.props = props
        self.deviceInfoData = deviceInfo ?? (try! Fixture.data("deviceinfo"))
        (events, eventContinuation) = AsyncStream.makeStream()
    }

    var sent: [Sent] { locked { _sent } }
    var extInfoEmptyReplies: Int {
        get { locked { _extInfoEmptyReplies } }
        set { locked { _extInfoEmptyReplies = newValue } }
    }
    var applyWrites: Bool {
        get { locked { _applyWrites } }
        set { locked { _applyWrites = newValue } }
    }
    var liveViewReplies: [PTPReply] {
        get { locked { _liveViewReplies } }
        set { locked { _liveViewReplies = newValue } }
    }
    var disconnectOnNext: UInt16? {
        get { locked { _disconnectOnNext } }
        set { locked { _disconnectOnNext = newValue } }
    }

    func emit(_ event: PTPEvent) { eventContinuation.yield(event) }

    /// «Покрутили колесо на камере».
    func setCurrent(_ code: UInt16, _ value: Int64) {
        locked {
            if let i = props.firstIndex(where: { $0.code == code }) { props[i].current = value }
        }
    }

    func send(_ operation: UInt16, _ params: [UInt32], outData: Data?) async throws -> PTPReply {
        try locked {
            _sent.append((operation, params, outData))
            if _disconnectOnNext == operation {
                _disconnectOnNext = nil
                eventContinuation.finish()
                throw TransportError.disconnected
            }
            let ok = PTPResponseCode.ok
            switch operation {
            case SonyOp.getDeviceInfo:
                return PTPReply(code: ok, data: deviceInfoData)
            case SonyOp.connect:
                return PTPReply(code: ok, data: Data(count: 8))
            case SonyOp.getExtDeviceInfo:
                if _extInfoEmptyReplies > 0 {
                    _extInfoEmptyReplies -= 1
                    return PTPReply(code: ok)
                }
                return PTPReply(code: ok, data: try Fixture.data("sony_extdeviceinfo"))
            case SonyOp.setExtPropValue:
                if _applyWrites, let code = params.first, let data = outData,
                   let i = props.firstIndex(where: { UInt32($0.code) == code }) {
                    var r = ByteReader(data)
                    if let v = try PTPValue.read(&r, type: props[i].type).intValue { props[i].current = v }
                }
                return PTPReply(code: ok)
            case SonyOp.controlDevice:
                // отпускание кнопки записи переключает состояние записи
                if params.first == UInt32(SonyProp.movieRecButton), outData == Data([0x01, 0x00]),
                   let i = props.firstIndex(where: { $0.code == SonyProp.movieRecordingState }) {
                    props[i].current = props[i].current == 0 ? 1 : 0
                }
                return PTPReply(code: ok)
            case SonyOp.getAllExtPropInfo:
                return PTPReply(code: ok, data: encodedProps())
            case SonyOp.getObject:
                guard !_liveViewReplies.isEmpty else { return PTPReply(code: PTPResponseCode.accessDenied) }
                return _liveViewReplies.removeFirst()
            default:
                return PTPReply(code: 0x2005)
            }
        }
    }

    /// Минимальный набор DeviceInfo с заданным списком операций.
    static func deviceInfo(operations: [UInt16]) -> Data {
        var d = Data()
        d.appendLE(UInt16(100))
        d.appendLE(UInt32(0x11))
        d.appendLE(UInt16(100))
        d.append(ptpString(""))
        d.appendLE(UInt16(0))
        for list in [operations, [], [], [], []] {
            d.appendLE(UInt32(list.count))
            for code in list { d.appendLE(code) }
        }
        for s in ["Sony Corporation", "ZV-E10", "2.00", "0"] { d.append(ptpString(s)) }
        return d
    }

    private static func ptpString(_ s: String) -> Data {
        guard !s.isEmpty else { return Data([0]) }
        let units = Array(s.utf16) + [0]
        var d = Data([UInt8(units.count)])
        for u in units { d.appendLE(u) }
        return d
    }

    private func encodedProps() -> Data {
        var d = Data()
        d.appendLE(UInt32(props.count))
        d.appendLE(UInt32(0))
        for p in props {
            d.appendLE(p.code)
            d.appendLE(p.type)
            d.append(p.getSet)
            d.append(p.enabled)
            d.append(PTPValue.encode(p.current, type: p.type))
            d.append(PTPValue.encode(p.current, type: p.type))
            d.append(2)
            d.appendLE(UInt16(p.values.count))
            for v in p.values { d.append(PTPValue.encode(v, type: p.type)) }
        }
        return d
    }

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }
}

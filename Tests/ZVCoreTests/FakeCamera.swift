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
    private var _timeoutOn: Set<UInt16> = []
    private var _busyOnNextPress: UInt16?
    private var _busyOnNextRelease: UInt16?
    private var _capturedFiles: [(String, Data)] = []
    private var memory: [(String, Data)] = []
    /// Снятое, но ещё не появившееся в памяти: камера обрабатывает снимок столько перечитываний свойств.
    private var processing: [(String, Data)] = []
    private var processingReads = 0
    private var _captureDelayReads = 0
    /// Ответы тестовой камеры на операции, которых у ZV-E10 нет (0x9215, 0x9216, 0x9223): по параметрам запроса.
    private var answers: [UInt16: @Sendable ([UInt32]) -> PTPReply] = [:]

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
    /// Что камера «снимет» при следующем нажатии спуска (имя файла и содержимое).
    var capturedFiles: [(String, Data)] {
        get { locked { _capturedFiles } }
        set { locked { _capturedFiles = newValue } }
    }
    /// Файлы, оставшиеся в памяти камеры от прошлого снимка (не забранные).
    func putInMemory(_ files: [(String, Data)]) {
        locked {
            memory += files
            setMemoryCount()
        }
    }

    var objectsInMemory: Int { locked { memory.count } }

    /// Сколько перечитываний свойств после спуска снимок «обрабатывается», прежде чем появится в памяти.
    var captureDelayReads: Int {
        get { locked { _captureDelayReads } }
        set { locked { _captureDelayReads = newValue } }
    }

    var disconnectOnNext: UInt16? {
        get { locked { _disconnectOnNext } }
        set { locked { _disconnectOnNext = newValue } }
    }
    /// На эти операции камера не отвечает: транспорт ждёт ответа до своего тайм-аута и бросает `TransportError.timeout`. Каждый
    /// запрос записывается в `sent`, как и обычный.
    var timeoutOn: Set<UInt16> {
        get { locked { _timeoutOn } }
        set { locked { _timeoutOn = newValue } }
    }
    /// Следующее нажатие этой кнопки (0x9207, значение 2) камера отклонит с DeviceBusy.
    var busyOnNextPress: UInt16? {
        get { locked { _busyOnNextPress } }
        set { locked { _busyOnNextPress = newValue } }
    }
    /// Следующее отпускание этой кнопки (значение 1) камера отклонит с DeviceBusy и ничего не сделает.
    var busyOnNextRelease: UInt16? {
        get { locked { _busyOnNextRelease } }
        set { locked { _busyOnNextRelease = newValue } }
    }

    func emit(_ event: PTPEvent) { eventContinuation.yield(event) }

    /// Камера отвечает на `operation` так: параметры запроса → ответ. Без этого такая операция получает 0x2005, как у ZV-E10.
    /// Ответ строится под замком камеры: обращаться к самой камере из него нельзя.
    func answer(_ operation: UInt16, _ reply: @escaping @Sendable ([UInt32]) -> PTPReply) {
        locked { answers[operation] = reply }
    }

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
            if _timeoutOn.contains(operation) { throw TransportError.timeout(operation: operation) }
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
                if let busy = _busyOnNextPress, params.first == UInt32(busy), outData == Data([0x02, 0x00]) {
                    _busyOnNextPress = nil
                    return PTPReply(code: PTPResponseCode.deviceBusy)
                }
                if let busy = _busyOnNextRelease, params.first == UInt32(busy), outData == Data([0x01, 0x00]) {
                    _busyOnNextRelease = nil
                    return PTPReply(code: PTPResponseCode.deviceBusy)
                }
                // кнопка записи 0xD2C8 — «как удержание»: нажатие начинает запись, отпускание останавливает
                if params.first == UInt32(SonyProp.movieRecButton),
                   let i = props.firstIndex(where: { $0.code == SonyProp.movieRecordingState }) {
                    if outData == Data([0x02, 0x00]), props[i].current == 0 { props[i].current = 1 }
                    if outData == Data([0x01, 0x00]), props[i].current == 1 { props[i].current = 0 }
                }
                // отпускание спуска — снимок готов в памяти камеры
                if params.first == UInt32(SonyProp.shutterFullPress), outData == Data([0x01, 0x00]), !_capturedFiles.isEmpty {
                    if _captureDelayReads > 0 {
                        processing = _capturedFiles
                        processingReads = _captureDelayReads
                    } else {
                        memory += _capturedFiles
                    }
                    _capturedFiles = []
                    setMemoryCount()
                }
                return PTPReply(code: ok)
            case SonyOp.getObjectInfo:
                if params.first == Sony.capturedObjectHandle, let first = memory.first {
                    return PTPReply(code: ok, data: Self.objectInfo(filename: first.0, size: first.1.count, format: 0x3801))
                }
                return PTPReply(code: PTPResponseCode.invalidObjectHandle)
            case SonyOp.getAllExtPropInfo:
                if !processing.isEmpty {
                    if processingReads > 0 {
                        processingReads -= 1
                    } else {
                        memory += processing
                        processing = []
                        setMemoryCount()
                    }
                }
                return PTPReply(code: ok, data: encodedProps())
            case SonyOp.getObject:
                if params.first == Sony.capturedObjectHandle {
                    guard !memory.isEmpty else { return PTPReply(code: PTPResponseCode.invalidObjectHandle) }
                    let file = memory.removeFirst()
                    setMemoryCount()
                    return PTPReply(code: ok, data: file.1)
                }
                guard !_liveViewReplies.isEmpty else { return PTPReply(code: PTPResponseCode.accessDenied) }
                return _liveViewReplies.removeFirst()
            default:
                if let reply = answers[operation] { return reply(params) }
                return PTPReply(code: 0x2005)
            }
        }
    }

    /// ObjectInfo (как ответ GetObjectInfo) с заданным именем, размером и форматом.
    static func objectInfo(filename: String, size: Int, format: UInt16) -> Data {
        var d = Data()
        d.appendLE(UInt32(0x0001_0001))      // StorageID
        d.appendLE(format)                   // ObjectFormat
        d.appendLE(UInt16(0))                // ProtectionStatus
        d.appendLE(UInt32(size))             // ObjectCompressedSize
        d.appendLE(UInt16(0))                // ThumbFormat
        for _ in 0..<7 { d.appendLE(UInt32(0)) }  // ThumbCompressedSize … ParentObject
        d.appendLE(UInt16(0))                // AssociationType
        d.appendLE(UInt32(0))                // AssociationDesc
        d.appendLE(UInt32(0))                // SequenceNumber
        d.append(ptpString(filename))
        for _ in 0..<3 { d.append(ptpString("")) }  // даты и ключевые слова
        return d
    }

    /// 0xD215: больше 0x8000 — в памяти камеры есть снимок по адресу 0xFFFFC001.
    private func setMemoryCount() {
        if let i = props.firstIndex(where: { $0.code == SonyProp.objectInMemory }) {
            props[i].current = memory.isEmpty ? 0 : Int64(0x8000 + memory.count)
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

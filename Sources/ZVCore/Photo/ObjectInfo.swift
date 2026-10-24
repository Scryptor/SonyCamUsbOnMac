import Foundation

/// Стандартный набор PTP ObjectInfo (ответ на GetObjectInfo) — нужны имя файла, размер и формат.
public struct ObjectInfo: Equatable, Sendable {
    public let format: UInt16
    public let compressedSize: UInt32
    public let filename: String

    public init(data: Data) throws {
        var r = ByteReader(data)
        _ = try r.u32()             // StorageID
        format = try r.u16()
        _ = try r.u16()             // ProtectionStatus
        compressedSize = try r.u32()
        _ = try r.u16()             // ThumbFormat
        try r.skip(4 * 7)           // ThumbCompressedSize … ParentObject
        _ = try r.u16()             // AssociationType
        try r.skip(4 * 2)           // AssociationDesc, SequenceNumber
        filename = try r.ptpString()
    }
}

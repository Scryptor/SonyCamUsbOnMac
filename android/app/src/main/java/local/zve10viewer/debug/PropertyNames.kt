package local.zve10viewer.debug

import local.zve10viewer.settings.SettingID

/**
 * Имена свойств PTP и Sony для панели отладки. Свои настройки программа называет сама (`SettingID.title`), остальные —
 * по libgphoto2 (camlibs/ptp2/ptp.h), как в программе для Mac.
 */
object PropertyNames {
    /** Имя по libgphoto2; null — такого свойства там нет. */
    fun name(code: Int): String? = names[code]

    /** Как называть свойство на панели: как в программе, иначе по libgphoto2, иначе «—». */
    fun title(code: Int): String = SettingID.entries.firstOrNull { it.code == code }?.title ?: names[code] ?: "—"

    private val names: Map<Int, String> = mapOf(
        0x5000 to "Undefined", 0x5001 to "BatteryLevel", 0x5002 to "FunctionalMode", 0x5003 to "ImageSize",
        0x5004 to "CompressionSetting", 0x5005 to "WhiteBalance", 0x5006 to "RGBGain", 0x5007 to "FNumber",
        0x5008 to "FocalLength", 0x5009 to "FocusDistance", 0x500A to "FocusMode", 0x500B to "ExposureMeteringMode",
        0x500C to "FlashMode", 0x500D to "ExposureTime", 0x500E to "ExposureProgramMode", 0x500F to "ExposureIndex",
        0x5010 to "ExposureBiasCompensation", 0x5011 to "DateTime", 0x5012 to "CaptureDelay",
        0x5013 to "StillCaptureMode", 0x5014 to "Contrast", 0x5015 to "Sharpness", 0x5016 to "DigitalZoom",
        0x5017 to "EffectMode", 0x5018 to "BurstNumber", 0x5019 to "BurstInterval", 0x501A to "TimelapseNumber",
        0x501B to "TimelapseInterval", 0x501C to "FocusMeteringMode", 0x501D to "UploadURL", 0x501E to "Artist",
        0x501F to "CopyrightInfo", 0x5020 to "SupportedStreams", 0x5021 to "EnabledStreams", 0x5022 to "VideoFormat",
        0x5023 to "VideoResolution", 0x5024 to "VideoQuality", 0x5025 to "VideoFrameRate", 0x5026 to "VideoContrast",
        0x5027 to "VideoBrightness", 0x5028 to "AudioFormat", 0x5029 to "AudioBitrate",
        0x502A to "AudioSamplingRate", 0x502B to "AudioBitPerSample", 0x502C to "AudioVolume",
        0xD001 to "IrisModeSetting", 0xD004 to "FocalDistanceInMeter", 0xD005 to "FocalDistanceInFeet",
        0xD006 to "FocalDistanceUnitSetting", 0xD007 to "FocusModeSetting", 0xD0D9 to "ImageStabilization",
        0xD0DB to "SilentMode", 0xD0DF to "ShutterType", 0xD156 to "ShutterLagTiming", 0xD200 to "DPCCompensation",
        0xD201 to "DRangeOptimize", 0xD203 to "ImageSize", 0xD20D to "ShutterSpeed",
        0xD20E to "BatteryLevelIndicator", 0xD20F to "ColorTemp", 0xD210 to "CCFilter", 0xD211 to "AspectRatio",
        0xD213 to "FocusFound", 0xD214 to "Zoom", 0xD215 to "ObjectInMemory", 0xD216 to "ExposeIndex",
        0xD217 to "AELockIndication", 0xD218 to "BatteryLevel", 0xD219 to "SensorCrop", 0xD21B to "PictureEffect",
        0xD21C to "ABFilter", 0xD21D to "MovieRecordingState", 0xD21E to "ISO", 0xD21F to "FELockIndication",
        0xD221 to "LiveViewStatus", 0xD222 to "StillImageStoreDestination", 0xD223 to "DateTimeSet",
        0xD224 to "ExposureCompensation", 0xD226 to "ISO2", 0xD227 to "FormatMediaStatus", 0xD229 to "ShutterSpeed2",
        0xD22C to "FocusArea", 0xD22D to "FocusMagnifierStatus", 0xD22F to "CurrentFocusMagnifierRatio",
        0xD230 to "FocusMagnifierPosition", 0xD231 to "LiveViewSettingEffect",
        0xD235 to "ManualFocusAdjustEnableStatus", 0xD239 to "PixelShiftShootingMode2",
        0xD23A to "PixelShiftShootingNumber", 0xD23B to "PixelShiftShootingInterval",
        0xD23C to "PixelShiftShootingStatus", 0xD23D to "PixelShiftShootingProgress", 0xD23F to "PictureProfile",
        0xD240 to "CreativeStyle", 0xD241 to "FileFormatMovie", 0xD242 to "RecordingSettingMovie",
        0xD248 to "MediaSLOT1Status", 0xD249 to "MediaSLOT1RemainingShots", 0xD24A to "MediaSLOT1ShootingTime",
        0xD24C to "FocalPosition", 0xD24E to "AWBLockIndication", 0xD24F to "IntervalRECModel",
        0xD250 to "IntervalRECStatus", 0xD251 to "DeviceOverheatStatus", 0xD252 to "JpegQuality",
        0xD253 to "CompressionSetting", 0xD254 to "FocusMagnifierSetting", 0xD255 to "AFTrackingSensitivity",
        0xD256 to "MediaSLOT2Status", 0xD257 to "MediaSLOT2RemainingShots", 0xD258 to "MediaSLOT2ShootingTime",
        0xD25A to "PriorityMode", 0xD25B to "ZoomEnableStatus", 0xD25C to "ZoomScale",
        0xD25D to "ZoomBarInformation", 0xD25E to "ZoomSpeedRange", 0xD25F to "ZoomSetting",
        0xD260 to "ZoomTypeStatus", 0xD262 to "WirelessFlashSetting", 0xD263 to "RedEyeReduction",
        0xD264 to "RemoteControlRestrictionStatus", 0xD267 to "LiveViewArea", 0xD268 to "PcSaveImageSize",
        0xD269 to "PcSaveImageFormat", 0xD26A to "LiveViewImageQuality", 0xD26B to "CustomWBCaptureArea",
        0xD26C to "CustomWBCaptureFrameSize", 0xD26D to "CustomWBCaptureStandbyEnable",
        0xD26E to "CustomWBCaptureStandbyCancelEnable", 0xD26F to "CustomWBCaptureEnable",
        0xD270 to "CustomWBExecutionState", 0xD271 to "SettingsSaveEnable", 0xD272 to "SettingsReadEnable",
        0xD273 to "SettingsSaveReadState", 0xD274 to "FTPSettingSaveEnable", 0xD275 to "FTPSettingReadEnable",
        0xD276 to "FTPSettingSaveReadState", 0xD2C1 to "ShutterHalfRelease", 0xD2C2 to "ShutterRelease",
        0xD2C3 to "AELButton", 0xD2C4 to "AFLButton", 0xD2C5 to "ReleaseLock", 0xD2C7 to "RequestOneShooting",
        0xD2C8 to "MovieRecButtonHold", 0xD2C9 to "FELButton", 0xD2CA to "FormatMedia", 0xD2CB to "FocusMagnifier",
        0xD2CC to "FocusMagnifierCancel", 0xD2CD to "RemoteKeyUp", 0xD2CE to "RemoteKeyDown",
        0xD2CF to "RemoteKeyLeft", 0xD2D0 to "RemoteKeyRight", 0xD2D1 to "ManualFocusAdjust", 0xD2D2 to "AFMFHold",
        0xD2D3 to "CancelPixelShiftShooting", 0xD2D4 to "PixelShiftShootingMode", 0xD2D5 to "HFRStandby",
        0xD2D6 to "HFRRecordingCancel", 0xD2D7 to "FocusStepNear", 0xD2D8 to "FocusStepFar", 0xD2D9 to "AWBLButton",
        0xD2DC to "AFAreaPosition", 0xD2DD to "ZoomOperation", 0xD2DF to "CustomWBCaptureStandby",
        0xD2E0 to "CustomWBCaptureStandbyCancel", 0xD2E1 to "CustomWBCapture", 0xD2E2 to "FormatMediaCard",
        0xD2E4 to "RemoteTouch", 0xD2E5 to "RemoteTouchCancel", 0xD2E9 to "SaveZoomAndFocusPosition",
        0xD2EA to "LoadZoomAndFocusPosition",
    )
}

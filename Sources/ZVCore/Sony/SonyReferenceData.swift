// Создано scripts/sony_reference.py из Camera Control PTP 3 Reference — правьте через скрипт или JSON в обоих местах
// (второе место — android/app/src/main/resources/sony_reference.json; тест androidCopyMatches сверяет их побайтно).

/// JSON справочника Sony: имена свойств и списки значений. Разбирает его SonyReferenceTables.
enum SonyReferenceData {
    static let json = #"""
{
 "source": "Camera Control PTP 3 Reference",
 "properties": {
  "0x5005": {
   "name": "White Balance",
   "values": {
    "0x0001": "Manual",
    "0x0002": "AWB",
    "0x0003": "One-push Automatic",
    "0x0004": "Daylight",
    "0x0005": "Fluorescent",
    "0x0006": "Tungsten (Incandescent)",
    "0x0007": "Flash",
    "0x8001": "Fluor.: Warm White (-1)",
    "0x8002": "Fluor.: Cool White (0)",
    "0x8003": "Fluor.: Day White (+1)",
    "0x8004": "Fluor.: Daylight White (+2)",
    "0x8010": "Cloudy",
    "0x8011": "Shade",
    "0x8012": "C.Temp.",
    "0x8020": "Custom 1",
    "0x8021": "Custom 2",
    "0x8022": "Custom 3",
    "0x8023": "Custom",
    "0x8030": "Underwater Auto"
   }
  },
  "0x5007": {
   "name": "F-Number",
   "values": {
    "0x0064": "1",
    "0x006E": "1.1",
    "0x0078": "1.2",
    "0x0082": "1.3",
    "0x008C": "1.4",
    "0x00A0": "1.6",
    "0x00AA": "1.7",
    "0x00B4": "1.8",
    "0x00C8": "2",
    "0x00DC": "2.2",
    "0x00F0": "2.4",
    "0x00FA": "2.5",
    "0x0118": "2.8",
    "0x0136": "3.1",
    "0x0140": "3.2",
    "0x0154": "3.4",
    "0x015E": "3.5",
    "0x0172": "3.7",
    "0x0190": "4",
    "0x01B8": "4.4",
    "0x01C2": "4.5",
    "0x01E0": "4.8",
    "0x01F4": "5",
    "0x0208": "5.2",
    "0x0230": "5.6",
    "0x026C": "6.2",
    "0x0276": "6.3",
    "0x029E": "6.7",
    "0x02A8": "6.8",
    "0x02C6": "7.1",
    "0x02DA": "7.3",
    "0x0320": "8",
    "0x0366": "8.7",
    "0x0384": "9",
    "0x03B6": "9.5",
    "0x03C0": "9.6",
    "0x03E8": "10",
    "0x044C": "11",
    "0x0514": "13",
    "0x0578": "14",
    "0x0640": "16",
    "0x0708": "18",
    "0x076C": "19",
    "0x07D0": "20",
    "0x0898": "22",
    "0x09C4": "25",
    "0x0A8C": "27",
    "0x0B54": "29",
    "0x0C80": "32",
    "0x0E10": "36",
    "0x0ED8": "38",
    "0x0FA0": "40",
    "0x1194": "45",
    "0x13EC": "51",
    "0x1518": "54",
    "0x1644": "57",
    "0x1900": "64",
    "0x1C20": "72",
    "0x1DB0": "76",
    "0x1FA4": "81",
    "0x2328": "90",
    "0xFFFD": "Iris Close",
    "0xFFFE": "--",
    "0xFFFF": "nothing to display"
   }
  },
  "0x500A": {
   "name": "Focus Mode",
   "values": {
    "0x0001": "Manual(MF)",
    "0x0002": "Automatic(AF_S)",
    "0x0003": "Automatic Macro(close-up)",
    "0x8004": "Continuous AF(AF_C)",
    "0x8005": "Auto(AF_A)",
    "0x8006": "Direct Manual Focus(DMF)",
    "0x8007": "Manual Focus Reverse(MF_R)",
    "0x8008": "(AF-D)",
    "0x8009": "Preset Focus(PF)"
   }
  },
  "0x500B": {
   "name": "Exposure Metering Mode",
   "values": {
    "0x0001": "Average",
    "0x0002": "Center-weighted-average",
    "0x0003": "Multi-spot",
    "0x0004": "Center-spot",
    "0x8001": "Multi",
    "0x8002": "Center-weighted",
    "0x8003": "Entire Screen Avg.",
    "0x8004": "Spot : Standard",
    "0x8005": "Spot : Large",
    "0x8006": "Highlight",
    "0x8011": "Standard",
    "0x8012": "Backlight",
    "0x8013": "Spotlight"
   }
  },
  "0x500C": {
   "name": "Flash Mode",
   "values": {
    "0x0001": "Auto flash",
    "0x0002": "Flash off",
    "0x0003": "Fill flash",
    "0x0004": "Red eye auto",
    "0x0005": "Red eye fill",
    "0x0006": "External Sync",
    "0x8001": "Slow Sync",
    "0x8003": "Rear Sync",
    "0x8004": "Wireless",
    "0x8021": "HSS auto",
    "0x8022": "HSS fill",
    "0x8024": "HSS WL",
    "0x8031": "Slow Sync Red Eye On",
    "0x8032": "Slow Sync Red Eye Off",
    "0x8041": "Slow Sync Wireless",
    "0x8042": "Rear Sync Wireless"
   }
  },
  "0x500E": {
   "name": "Exposure Program Mode",
   "values": {
    "0x00000001": "Manual (M)",
    "0x00000005": "Program Creative (Greater Depth of Field)",
    "0x00000006": "Program Action (Faster Shutter Speed)",
    "0x00000007": "Portrait",
    "0x00008008": "P_A",
    "0x00008009": "P_S",
    "0x00008020": "Memory Recall (MR)",
    "0x00008030": "Continuous Priority AE",
    "0x00008031": "Tele-Zoom Continuous Priority AE 8pics",
    "0x00008032": "Tele-Zoom Continuous Priority AE 10pics",
    "0x00008033": "Continuous Priority AE12pics",
    "0x00008060": "Flash Off",
    "0x00008070": "Picture Effect",
    "0x00008084": "S&Q Motion (P)",
    "0x00008085": "S&Q Motion (A)",
    "0x00008086": "S&Q Motion (S)",
    "0x00008087": "S&Q Motion (M)",
    "0x00010002": "Automatic (P)",
    "0x00020003": "Aperture Priority (A)",
    "0x00030004": "Shutter Priority (S)",
    "0x00048000": "Auto",
    "0x00048001": "Auto+",
    "0x00058011": "Sports Action",
    "0x00058012": "Sunset",
    "0x00058013": "Night Scene",
    "0x00058014": "Landscape",
    "0x00058015": "Macro",
    "0x00058016": "Hand-held Twilight",
    "0x00058017": "Night Portrait",
    "0x00058018": "Anti Motion Blur",
    "0x00058019": "Pet",
    "0x0005801A": "Gourmet",
    "0x0005801B": "Fireworks",
    "0x0005801C": "High Sensitivity",
    "0x00068040": "3D Sweep Panorama Shooting",
    "0x00068041": "Sweep Panorama Shooting",
    "0x00078050": "Movie Recording (P)",
    "0x00078051": "Movie Recording (A)",
    "0x00078052": "Movie Recording (S)",
    "0x00078053": "Movie Recording (M)",
    "0x00078054": "Movie Recording (Auto)",
    "0x00078090": "Movie F Mode",
    "0x00088080": "High Frame Rate (P)",
    "0x00088081": "High Frame Rate (A)",
    "0x00088082": "High Frame Rate (S)",
    "0x00088083": "High Frame Rate (M)",
    "0x00098059": "Movie Recording (S&Q Motion (P))",
    "0x0009805A": "Movie Recording (S&Q Motion (A))",
    "0x0009805B": "Movie Recording (S&Q Motion (S))",
    "0x0009805C": "Movie Recording (S&Q Motion (M))",
    "0x0009805D": "Movie Recording (S&Q Motion (Auto))",
    "0x00098091": "S&Q F Mode",
    "0x000A8088": "Movie",
    "0x000A8089": "Still",
    "0x000B808A": "F (Movie or S&Q)",
    "0x000C8092": "Interval REC (Movie) F Mode",
    "0x000C8093": "Interval REC (Movie) (P)",
    "0x000C8094": "Interval REC (Movie) (A)",
    "0x000C8095": "Interval REC (Movie) (S)",
    "0x000C8096": "Interval REC (Movie) (M)",
    "0x000C8097": "Interval REC (Movie) (Auto)"
   }
  },
  "0x5010": {
   "name": "Exposure Bias Compensation"
  },
  "0x5013": {
   "name": "Still Capture Mode",
   "values": {
    "0x00000001": "Normal",
    "0x00010002": "Continuous Shooting Hi",
    "0x00018010": "Continuous Shooting Hi+",
    "0x00018011": "Continuous Shooting Hi-Live",
    "0x00018012": "Continuous Shooting Lo",
    "0x00018013": "Continuous Shooting",
    "0x00018014": "Continuous Shooting Speed Priority",
    "0x00018015": "Continuous Shooting Mid",
    "0x00018016": "Continuous Shooting Mid-Live",
    "0x00018017": "Continuous Shooting Lo-Live",
    "0x00020003": "Timelapse",
    "0x00038003": "Self Timer 5 Sec.",
    "0x00038004": "Self Timer 10 Sec.",
    "0x00038005": "Self Timer 2 Sec.",
    "0x00048311": "Continuous Bracket 1.0 EV 3 Img.",
    "0x00048321": "Continuous Bracket 2.0 EV 3 Img.",
    "0x00048331": "Continuous Bracket 3.0 EV 3 Img.",
    "0x00048337": "Continuous Bracket 0.3 EV 3 Img.",
    "0x00048341": "Continuous Bracket 1.3 EV 3 Img.",
    "0x00048351": "Continuous Bracket 2.3 EV 3 Img.",
    "0x00048357": "Continuous Bracket 0.5 EV 3 Img.",
    "0x00048361": "Continuous Bracket 1.5 EV 3 Img.",
    "0x00048371": "Continuous Bracket 2.5 EV 3 Img.",
    "0x00048377": "Continuous Bracket 0.7 EV 3 Img.",
    "0x00048381": "Continuous Bracket 1.7 EV 3 Img.",
    "0x00048391": "Continuous Bracket 2.7 EV 3 Img.",
    "0x00048511": "Continuous Bracket 1.0 EV 5 Img.",
    "0x00048521": "Continuous Bracket 2.0 EV 5 Img.",
    "0x00048531": "Continuous Bracket 3.0 EV 5 Img.",
    "0x00048537": "Continuous Bracket 0.3 EV 5 Img.",
    "0x00048541": "Continuous Bracket 1.3 EV 5 Img.",
    "0x00048551": "Continuous Bracket 2.3 EV 5 Img.",
    "0x00048557": "Continuous Bracket 0.5 EV 5 Img.",
    "0x00048561": "Continuous Bracket 1.5 EV 5 Img.",
    "0x00048571": "Continuous Bracket 2.5 EV 5 Img.",
    "0x00048577": "Continuous Bracket 0.7 EV 5 Img.",
    "0x00048581": "Continuous Bracket 1.7 EV 5 Img.",
    "0x00048591": "Continuous Bracket 2.7 EV 5 Img.",
    "0x00048711": "Continuous Bracket 1.0 EV 7 Img.",
    "0x00048721": "Continuous Bracket 2.0 EV 7 Img.",
    "0x00048737": "Continuous Bracket 0.3 EV 7 Img.",
    "0x00048741": "Continuous Bracket 1.3 EV 7 Img.",
    "0x00048757": "Continuous Bracket 0.5 EV 7 Img.",
    "0x00048761": "Continuous Bracket 1.5 EV 7 Img.",
    "0x00048777": "Continuous Bracket 0.7 EV 7 Img.",
    "0x00048781": "Continuous Bracket 1.7 EV 7 Img.",
    "0x00048911": "Continuous Bracket 1.0 EV 9 Img.",
    "0x00048937": "Continuous Bracket 0.3 EV 9 Img.",
    "0x00048957": "Continuous Bracket 0.5 EV 9 Img.",
    "0x00048977": "Continuous Bracket 0.7 EV 9 Img.",
    "0x0004C211": "Continuous Bracket 1.0 EV 2 Img. +",
    "0x0004C219": "Continuous Bracket 1.0 EV 2 Img. -",
    "0x0004C221": "Continuous Bracket 2.0 EV 2 Img. +",
    "0x0004C229": "Continuous Bracket 2.0 EV 2 Img. -",
    "0x0004C231": "Continuous Bracket 3.0 EV 2 Img. +",
    "0x0004C237": "Continuous Bracket 0.3 EV 2 Img. +",
    "0x0004C239": "Continuous Bracket 3.0 EV 2 Img. -",
    "0x0004C23F": "Continuous Bracket 0.3 EV 2 Img. -",
    "0x0004C241": "Continuous Bracket 1.3 EV 2 Img. +",
    "0x0004C249": "Continuous Bracket 1.3 EV 2 Img. -",
    "0x0004C251": "Continuous Bracket 2.3 EV 2 Img. +",
    "0x0004C257": "Continuous Bracket 0.5 EV 2 Img. +",
    "0x0004C259": "Continuous Bracket 2.3 EV 2 Img. -",
    "0x0004C25F": "Continuous Bracket 0.5 EV 2 Img. -",
    "0x0004C261": "Continuous Bracket 1.5 EV 2 Img. +",
    "0x0004C269": "Continuous Bracket 1.5 EV 2 Img. -",
    "0x0004C271": "Continuous Bracket 2.5 EV 2 Img. +",
    "0x0004C277": "Continuous Bracket 0.7 EV 2 Img. +",
    "0x0004C279": "Continuous Bracket 2.5 EV 2 Img. -",
    "0x0004C27F": "Continuous Bracket 0.7 EV 2 Img. -",
    "0x0004C281": "Continuous Bracket 1.7 EV 2 Img. +",
    "0x0004C289": "Continuous Bracket 1.7 EV 2 Img. -",
    "0x0004C291": "Continuous Bracket 2.7 EV 2 Img. +",
    "0x0004C299": "Continuous Bracket 2.7 EV 2 Img. -",
    "0x00058310": "Single Bracket 1.0 EV 3 Img.",
    "0x00058320": "Single Bracket 2.0 EV 3 Img.",
    "0x00058330": "Single Bracket 3.0 EV 3 Img.",
    "0x00058336": "Single Bracket 0.3 EV 3 Img.",
    "0x00058340": "Single Bracket 1.3 EV 3 Img.",
    "0x00058350": "Single Bracket 2.3 EV 3 Img.",
    "0x00058356": "Single Bracket 0.5 EV 3 Img.",
    "0x00058360": "Single Bracket 1.5 EV 3 Img.",
    "0x00058370": "Single Bracket 2.5 EV 3 Img.",
    "0x00058376": "Single Bracket 0.7 EV 3 Img.",
    "0x00058380": "Single Bracket 1.7 EV 3 Img.",
    "0x00058390": "Single Bracket 2.7 EV 3 Img.",
    "0x00058510": "Single Bracket 1.0 EV 5 Img.",
    "0x00058520": "Single Bracket 2.0 EV 5 Img.",
    "0x00058530": "Single Bracket 3.0 EV 5 Img.",
    "0x00058536": "Single Bracket 0.3 EV 5 Img.",
    "0x00058540": "Single Bracket 1.3 EV 5 Img.",
    "0x00058550": "Single Bracket 2.3 EV 5 Img.",
    "0x00058556": "Single Bracket 0.5 EV 5 Img.",
    "0x00058560": "Single Bracket 1.5 EV 5 Img.",
    "0x00058570": "Single Bracket 2.5 EV 5 Img.",
    "0x00058576": "Single Bracket 0.7 EV 5 Img.",
    "0x00058580": "Single Bracket 1.7 EV 5 Img.",
    "0x00058590": "Single Bracket 2.7 EV 5 Img.",
    "0x00058710": "Single Bracket 1.0 EV 7 Img.",
    "0x00058720": "Single Bracket 2.0 EV 7 Img.",
    "0x00058736": "Single Bracket 0.3 EV 7 Img.",
    "0x00058740": "Single Bracket 1.3 EV 7 Img.",
    "0x00058756": "Single Bracket 0.5 EV 7 Img.",
    "0x00058760": "Single Bracket 1.5 EV 7 Img.",
    "0x00058776": "Single Bracket 0.7 EV 7 Img.",
    "0x00058780": "Single Bracket 1.7 EV 7 Img.",
    "0x00058910": "Single Bracket 1.0 EV 9 Img.",
    "0x00058936": "Single Bracket 0.3 EV 9 Img.",
    "0x00058956": "Single Bracket 0.5 EV 9 Img.",
    "0x00058976": "Single Bracket 0.7 EV 9 Img.",
    "0x0005C210": "Single Bracket 1.0 EV 2 Img. +",
    "0x0005C218": "Single Bracket 1.0 EV 2 Img. -",
    "0x0005C220": "Single Bracket 2.0 EV 2 Img. +",
    "0x0005C228": "Single Bracket 2.0 EV 2 Img. -",
    "0x0005C230": "Single Bracket 3.0 EV 2 Img. +",
    "0x0005C236": "Single Bracket 0.3 EV 2 Img. +",
    "0x0005C238": "Single Bracket 3.0 EV 2 Img. -",
    "0x0005C23E": "Single Bracket 0.3 EV 2 Img. -",
    "0x0005C240": "Single Bracket 1.3 EV 2 Img. +",
    "0x0005C248": "Single Bracket 1.3 EV 2 Img. -",
    "0x0005C250": "Single Bracket 2.3 EV 2 Img. +",
    "0x0005C256": "Single Bracket 0.5 EV 2 Img. +",
    "0x0005C258": "Single Bracket 2.3 EV 2 Img. -",
    "0x0005C25E": "Single Bracket 0.5 EV 2 Img. -",
    "0x0005C260": "Single Bracket 1.5 EV 2 Img. +",
    "0x0005C268": "Single Bracket 1.5 EV 2 Img. -",
    "0x0005C270": "Single Bracket 2.5 EV 2 Img. +",
    "0x0005C276": "Single Bracket 0.7 EV 2 Img. +",
    "0x0005C278": "Single Bracket 2.5 EV 2 Img. -",
    "0x0005C27E": "Single Bracket 0.7 EV 2 Img. -",
    "0x0005C280": "Single Bracket 1.7 EV 2 Img. +",
    "0x0005C288": "Single Bracket 1.7 EV 2 Img. -",
    "0x0005C290": "Single Bracket 2.7 EV 2 Img. +",
    "0x0005C298": "Single Bracket 2.7 EV 2 Img. -",
    "0x00068018": "White Balance Bracket Lo",
    "0x00068028": "White Balance Bracket Hi",
    "0x00078006": "Self Portrait 1 Person",
    "0x00078007": "Self Portrait 2 People",
    "0x0007800A": "Remote Commander",
    "0x0007800B": "Mirror Up",
    "0x00078019": "DRO Bracket Lo",
    "0x0007801A": "LPF Bracket",
    "0x00078029": "DRO Bracket Hi",
    "0x00088008": "Continuous Self Timer 3 Img.",
    "0x00088009": "Continuous Self Timer 5 Img.",
    "0x0008800C": "Continuous Self Timer 3 Img. 5 Sec.",
    "0x0008800D": "Continuous Self Timer 5 Img. 5 Sec.",
    "0x0008800E": "Continuous Self Timer 3 Img. 2 Sec.",
    "0x0008800F": "Continuous Self Timer 5 Img. 2 Sec.",
    "0x00098030": "Spot Burst Shooting Lo",
    "0x00098031": "Spot Burst Shooting Mid",
    "0x00098032": "Spot Burst Shooting Hi",
    "0x000A8040": "Focus Bracket"
   }
  },
  "0xD000": {
   "name": "T-Number",
   "values": {
    "0xFFFD": "Iris Close",
    "0xFFFE": "--",
    "0xFFFF": "nothing to display"
   }
  },
  "0xD001": {
   "name": "Iris Mode Setting",
   "values": {
    "0x01": "Automatic",
    "0x02": "Manual"
   }
  },
  "0xD002": {
   "name": "Iris Close Setting",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD003": {
   "name": "Iris Display Unit",
   "values": {
    "0x01": "Auto",
    "0x02": "F",
    "0x03": "T"
   }
  },
  "0xD004": {
   "name": "Focal Distance in Meter"
  },
  "0xD005": {
   "name": "Focal Distance in Feet"
  },
  "0xD006": {
   "name": "Focal Distance Unit Setting",
   "values": {
    "0x01": "Meter",
    "0x02": "Feet"
   }
  },
  "0xD007": {
   "name": "Focus Mode Setting",
   "values": {
    "0x01": "Automatic",
    "0x02": "Manual"
   }
  },
  "0xD008": {
   "name": "Focus Speed Range"
  },
  "0xD00A": {
   "name": "Digital Zoom Scale"
  },
  "0xD00B": {
   "name": "Zoom Distance"
  },
  "0xD00C": {
   "name": "White Balance Mode Setting",
   "values": {
    "0x01": "Automatic",
    "0x02": "Manual"
   }
  },
  "0xD00D": {
   "name": "White Balance Tint"
  },
  "0xD00E": {
   "name": "Shutter Angle",
   "values": {
    "0x00000000": "-"
   }
  },
  "0xD00F": {
   "name": "Shutter Setting",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD010": {
   "name": "Shutter Mode",
   "values": {
    "0x01": "Speed",
    "0x02": "Angle"
   }
  },
  "0xD011": {
   "name": "Shutter Mode Status",
   "values": {
    "0x01": "OFF",
    "0x02": "Speed",
    "0x03": "Angle",
    "0x04": "ECS",
    "0x05": "Auto"
   }
  },
  "0xD012": {
   "name": "Shutter Select Mode",
   "values": {
    "0x01": "Step",
    "0x02": "Continue"
   }
  },
  "0xD013": {
   "name": "Shutter Mode Setting",
   "values": {
    "0x01": "Automatic",
    "0x02": "Manual"
   }
  },
  "0xD014": {
   "name": "Shutter Slow",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD015": {
   "name": "Shutter Slow Frames",
   "values": {
    "0x00": "-"
   }
  },
  "0xD016": {
   "name": "Shutter Speed Value"
  },
  "0xD017": {
   "name": "Shutter Speed Current Value"
  },
  "0xD018": {
   "name": "ND Filter",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD019": {
   "name": "ND Filter Mode",
   "values": {
    "0x01": "Auto",
    "0x02": "Preset",
    "0x03": "PresetClear",
    "0x04": "Variable",
    "0x05": "VariableClear",
    "0x06": "Step",
    "0x07": "StepClear"
   }
  },
  "0xD01A": {
   "name": "ND Filter Mode Setting",
   "values": {
    "0x01": "Automatic",
    "0x02": "Manual"
   }
  },
  "0xD01B": {
   "name": "ND Filter Value",
   "values": {
    "0xFFFFFFFFFFFFFFFF": "nothing to display."
   }
  },
  "0xD01C": {
   "name": "Gain Control Setting",
   "values": {
    "0x01": "Automatic",
    "0x02": "Manual"
   }
  },
  "0xD01D": {
   "name": "Gain Unit Setting",
   "values": {
    "0x01": "dB",
    "0x02": "ISO"
   }
  },
  "0xD01E": {
   "name": "Gain dB Value"
  },
  "0xD01F": {
   "name": "Gain dB Current Value"
  },
  "0xD020": {
   "name": "Gain Base ISO Sensitivity",
   "values": {
    "0x01": "High level",
    "0x02": "Low level"
   }
  },
  "0xD021": {
   "name": "Gain Base Sensitivity",
   "values": {
    "0x01": "High level",
    "0x02": "Low level"
   }
  },
  "0xD022": {
   "name": "Exposure Index"
  },
  "0xD023": {
   "name": "ISO Current Sensitivity",
   "values": {
    "0x0000000A": "ISO 10",
    "0x0000000C": "ISO 12",
    "0x00000010": "ISO 16",
    "0x00000014": "ISO 20",
    "0x00000019": "ISO 25",
    "0x00000020": "ISO 32",
    "0x00000028": "ISO 40",
    "0x00000032": "ISO 50",
    "0x00000040": "ISO 64",
    "0x00000050": "ISO 80",
    "0x00000064": "ISO 100",
    "0x0000007D": "ISO 125",
    "0x000000A0": "ISO 160",
    "0x000000C8": "ISO 200",
    "0x000000FA": "ISO 250",
    "0x00000140": "ISO 320",
    "0x00000190": "ISO 400",
    "0x000001F4": "ISO 500",
    "0x00000280": "ISO 640",
    "0x00000320": "ISO 800",
    "0x000003E8": "ISO 1000",
    "0x000004E2": "ISO 1250",
    "0x00000640": "ISO 1600",
    "0x000007D0": "ISO 2000",
    "0x000009C4": "ISO 2500",
    "0x00000C80": "ISO 3200",
    "0x00000FA0": "ISO 4000",
    "0x00001388": "ISO 5000",
    "0x00001900": "ISO 6400",
    "0x00001F40": "ISO 8000",
    "0x00002710": "ISO 10000",
    "0x00003200": "ISO 12800",
    "0x00003E80": "ISO 16000",
    "0x00004E20": "ISO 20000",
    "0x00006400": "ISO 25600",
    "0x00007D00": "ISO 32000",
    "0x00009C40": "ISO 40000",
    "0x0000C800": "ISO 51200",
    "0x0000FA00": "ISO 64000",
    "0x00013880": "ISO 80000",
    "0x00019000": "ISO 102400",
    "0x0001F400": "ISO 128000",
    "0x00027100": "ISO 160000",
    "0x00032000": "ISO 204800",
    "0x0003E800": "ISO 256000",
    "0x0004E200": "ISO 320000",
    "0x00064000": "ISO 409600",
    "0x0007D000": "ISO 512000",
    "0x0009C400": "ISO 640000",
    "0x000C8000": "ISO 819200"
   }
  },
  "0xD024": {
   "name": "Recording Resolution for Main (Movie)"
  },
  "0xD025": {
   "name": "Recording Resolution for Proxy (Movie)"
  },
  "0xD027": {
   "name": "Proxy File Format (Movie)",
   "values": {
    "0x01": "DVD",
    "0x02": "M2PS",
    "0x03": "AVCHD",
    "0x04": "MP4",
    "0x05": "DV",
    "0x06": "XAVC",
    "0x07": "MXF",
    "0x08": "XAVC S 4K",
    "0x09": "XAVC S HD",
    "0x0A": "XAVC HS 8K",
    "0x0B": "XAVC HS 4K",
    "0x0C": "XAVC S-L 4K",
    "0x0D": "XAVC S-L HD",
    "0x0E": "XAVC S-I 4K",
    "0x0F": "XAVC S-I HD",
    "0x10": "XAVC I",
    "0x11": "XAVC L",
    "0x12": "XAVC Proxy",
    "0x13": "XAVC HS HD",
    "0x14": "XAVC S-I DCI 4K",
    "0x15": "XAVC H-I HQ",
    "0x16": "XAVC H-I SQ",
    "0x17": "XAVC H-L",
    "0x18": "X-OCN XT",
    "0x19": "X-OCN ST",
    "0x1A": "X-OCN LT",
    "0x1B": "XAVC HS-L 422",
    "0x1C": "XAVC HS-L 420",
    "0x1D": "XAVC S-L 422",
    "0x1E": "XAVC S-L 420",
    "0x1F": "XAVC S-I 422",
    "0x20": "MPEG HD 422"
   }
  },
  "0xD028": {
   "name": "Recording Frame Rate Proxy Setting (Movie)",
   "values": {
    "0x01": "120p (*1)",
    "0x02": "100p",
    "0x03": "60p (*2)",
    "0x04": "50p",
    "0x05": "30p (*3)",
    "0x06": "25p",
    "0x07": "24p (*4)",
    "0x08": "23.98p",
    "0x09": "29.97p",
    "0x0A": "59.94p",
    "0x0B": "19.98p",
    "0x0C": "14.99p",
    "0x0D": "12.50p",
    "0x0E": "12.00p",
    "0x0F": "11.99p",
    "0x10": "10.00p",
    "0x11": "9.99p",
    "0x12": "6.00p",
    "0x13": "5.99p",
    "0x14": "5.00p",
    "0x15": "4.995p",
    "0x16": "24.00p",
    "0x17": "119.88p",
    "0x41": "120i (*1)",
    "0x42": "100i",
    "0x43": "60i (*2)",
    "0x44": "50i",
    "0x45": "30i (*3)",
    "0x46": "25i",
    "0x47": "24i (*4)",
    "0x48": "23.98i",
    "0x49": "29.97i",
    "0x4A": "59.94i",
    "0x4B": "19.98i",
    "0x4C": "14.99i",
    "0x4D": "12.50i",
    "0x4E": "12.00i",
    "0x4F": "11.99i",
    "0x50": "10.00i",
    "0x51": "9.99i",
    "0x52": "6.00i",
    "0x53": "5.99i",
    "0x54": "5.00i",
    "0x55": "4.995i",
    "0x56": "24.00i",
    "0x57": "119.88i"
   }
  },
  "0xD029": {
   "name": "Zoom Distance Unit Setting",
   "values": {
    "0x01": "mm",
    "0x02": "percent"
   }
  },
  "0xD02A": {
   "name": "Sync ID for FTP Job List"
  },
  "0xD02B": {
   "name": "Media SLOT1 Rec Available Type",
   "values": {
    "0x00": "None",
    "0x01": "Main",
    "0x02": "Proxy",
    "0x03": "Main + Proxy"
   }
  },
  "0xD02C": {
   "name": "Media SLOT2 Rec Available Type",
   "values": {
    "0x00": "None",
    "0x01": "Main",
    "0x02": "Proxy",
    "0x03": "Main + Proxy"
   }
  },
  "0xD02E": {
   "name": "Select FTP ServerID",
   "values": {
    "0xFFFFFFFF": "nothing to display."
   }
  },
  "0xD02F": {
   "name": "Movie Playing State",
   "values": {
    "0x00": "Not Playing",
    "0x01": "Playing"
   }
  },
  "0xD030": {
   "name": "Movie Playing Speed",
   "values": {
    "0xFFFFFFFFFFFFFFFF": "nothing to display."
   }
  },
  "0xD031": {
   "name": "Media SLOT1 ProfileUrl"
  },
  "0xD032": {
   "name": "Media SLOT2 ProfileUrl"
  },
  "0xD035": {
   "name": "Media SLOT1 Player",
   "values": {
    "0x00": "None",
    "0x01": "Player",
    "0x02": "Recoder",
    "0x03": "Player + Recoder"
   }
  },
  "0xD036": {
   "name": "Media SLOT2 Player",
   "values": {
    "0x00": "None",
    "0x01": "Player",
    "0x02": "Recoder",
    "0x03": "Player + Recoder"
   }
  },
  "0xD037": {
   "name": "Battery Remain Display Unit",
   "values": {
    "0x01": "minute",
    "0x02": "percent",
    "0x03": "voltage"
   }
  },
  "0xD038": {
   "name": "Battery Remaining in Minutes"
  },
  "0xD039": {
   "name": "Battery Remaining in Voltage"
  },
  "0xD03A": {
   "name": "Power Source",
   "values": {
    "0x01": "DC",
    "0x02": "Battery",
    "0x03": "PoE"
   }
  },
  "0xD03B": {
   "name": "AWB",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xD03C": {
   "name": "BaseLook Value",
   "values": {
    "0x0001": "Index 1",
    "0x0002": "Index 2",
    "0x0003": "Index 3",
    "0x0004": "Index 4",
    "0x0005": "Index 5",
    "0x0006": "Index 6",
    "0x0007": "Index 7",
    "0x0008": "Index 8",
    "0x0009": "Index 9",
    "0x000A": "Index 10",
    "0x000B": "Index 11",
    "0x000C": "Index 12",
    "0x000D": "Index 13",
    "0x000E": "Index 14",
    "0x000F": "Index 15",
    "0x0010": "Index 16"
   }
  },
  "0xD03E": {
   "name": "DC Voltage"
  },
  "0xD040": {
   "name": "Software Version"
  },
  "0xD041": {
   "name": "FTP Function",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD042": {
   "name": "Playback Media",
   "values": {
    "0x01": "SLOT1",
    "0x02": "SLOT2"
   }
  },
  "0xD043": {
   "name": "REC Settings Reset Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD044": {
   "name": "Monitor DISP (Screen Display) Mode Candidates",
   "values": {
    "0x00000001": "Graphic Display",
    "0x00000002": "Display All Info.",
    "0x00000004": "Histogram",
    "0x00000008": "Level",
    "0x00000010": "No Disp. Info.",
    "0x00000020": "No Disp. Info. Exposure: On",
    "0x00000040": "No Disp. Info. Exposure: Timeout",
    "0x00000080": "For viewfinder",
    "0x00000100": "Monitor Off"
   }
  },
  "0xD045": {
   "name": "Monitor DISP (Screen Display) Mode Setting"
  },
  "0xD046": {
   "name": "Monitor DISP (Screen Display) Mode",
   "values": {
    "0x01": "Graphic Display",
    "0x02": "Display All Info.",
    "0x03": "No Disp. Info.",
    "0x04": "Histogram",
    "0x05": "Level",
    "0x06": "For viewfinder",
    "0x07": "Monitor Off"
   }
  },
  "0xD047": {
   "name": "Touch Operation",
   "values": {
    "0x01": "Off",
    "0x02": "On",
    "0x03": "On: Playback only"
   }
  },
  "0xD048": {
   "name": "Select Finder/Monitor",
   "values": {
    "0x01": "Auto",
    "0x02": "Viewfinder (Manual)",
    "0x03": "Monitor (Manual)",
    "0x04": "Auto2"
   }
  },
  "0xD049": {
   "name": "Auto Power OFF Temperature",
   "values": {
    "0x01": "Standard",
    "0x02": "High"
   }
  },
  "0xD04A": {
   "name": "Body Key Lock",
   "values": {
    "0x01": "Unlock",
    "0x02": "Lock"
   }
  },
  "0xD04B": {
   "name": "Image ID (Numerical Value)"
  },
  "0xD04C": {
   "name": "Image ID (String)"
  },
  "0xD04D": {
   "name": "Monitor LUT Setting (All Line)",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD04E": {
   "name": "Auto FTP Transfer",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD04F": {
   "name": "Auto FTP Transfer Target",
   "values": {
    "0x01": "Still",
    "0x02": "Movie",
    "0x03": "Still＆Movie"
   }
  },
  "0xD050": {
   "name": "Simul Rec Setting",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD051": {
   "name": "S&Q Mode Setting",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD052": {
   "name": "S&Q Frame Rate",
   "values": {
    "0x0000": "Invalid"
   }
  },
  "0xD053": {
   "name": "Picture Cache Rec Setting",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD054": {
   "name": "Picture Cache Rec Size and Time"
  },
  "0xD055": {
   "name": "Interval REC (Movie) Time",
   "values": {
    "0x00000001": "1 sec",
    "0x00000002": "2 sec",
    "0x00000003": "3 sec",
    "0x00000004": "4 sec",
    "0x00000005": "5 sec",
    "0x00000006": "6 sec",
    "0x00000007": "7 sec",
    "0x00000008": "8 sec",
    "0x00000009": "9 sec",
    "0x0000000A": "10 sec",
    "0x0000000F": "15 sec",
    "0x00000014": "20 sec",
    "0x0000001E": "30 sec",
    "0x00000028": "40 sec",
    "0x00000032": "50 sec",
    "0x0000003C": "1 min",
    "0x00000078": "2 min",
    "0x000000B4": "3 min",
    "0x000000F0": "4 min",
    "0x0000012C": "5 min",
    "0x00000168": "6 min",
    "0x000001A4": "7 min",
    "0x000001E0": "8 min",
    "0x0000021C": "9 min",
    "0x00000258": "10 min",
    "0x00000384": "15 min",
    "0x000004B0": "20 min",
    "0x00000708": "30 min",
    "0x00000960": "40 min",
    "0x00000BB8": "50 min",
    "0x00000E10": "1 hour",
    "0x00001C20": "2 hour",
    "0x00002A30": "3 hour",
    "0x00003840": "4 hour",
    "0x00005460": "6 hour",
    "0x0000A8C0": "12 hour",
    "0x00015180": "24 hour"
   }
  },
  "0xD056": {
   "name": "Interval REC (Movie) Frames",
   "values": {
    "0x0001": "1 frame",
    "0x0002": "2 frames",
    "0x0003": "3 frames",
    "0x0004": "4 frames",
    "0x0006": "6 frames",
    "0x0009": "9 frames",
    "0x000C": "12 frames",
    "0x0018": "24 frames"
   }
  },
  "0xD057": {
   "name": "Upload Dataset Version"
  },
  "0xD059": {
   "name": "BaseLookImport Command Version"
  },
  "0xD05B": {
   "name": "Push Auto Iris",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xD05C": {
   "name": "Push AGC",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xD05D": {
   "name": "Push Auto ND Filter",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xD05E": {
   "name": "Push Auto Focus",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xD05F": {
   "name": "Imager Scan Mode",
   "values": {
    "0x01": "Auto",
    "0x02": "Full Frame",
    "0x03": "S35"
   }
  },
  "0xD060": {
   "name": "Subject Recognition AF",
   "values": {
    "0x01": "Off",
    "0x02": "Only AF",
    "0x03": "Priority AF"
   }
  },
  "0xD061": {
   "name": "AF Transition Speed"
  },
  "0xD062": {
   "name": "AF Subj Shift Sens"
  },
  "0xD063": {
   "name": "Recording Resolution For RAW (Movie)"
  },
  "0xD064": {
   "name": "Touch Function in MF",
   "values": {
    "0x01": "Tracking AF",
    "0x02": "Spot Focus"
   }
  },
  "0xD065": {
   "name": "Paint/Look Master Black"
  },
  "0xD066": {
   "name": "Paint/Look R Black"
  },
  "0xD067": {
   "name": "Paint/Look B Black"
  },
  "0xD068": {
   "name": "Paint/Look Knee Setting",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD069": {
   "name": "Paint/Look Auto Knee",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD06A": {
   "name": "Paint/Look Knee Point"
  },
  "0xD06B": {
   "name": "Paint/Look Knee Slope"
  },
  "0xD06C": {
   "name": "Paint/Look Detail Setting",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD06D": {
   "name": "Paint/Look Detail Level"
  },
  "0xD073": {
   "name": "ND Filter Switching Setting",
   "values": {
    "0x01": "PRESET",
    "0x02": "VARIABLE",
    "0x03": "STEP"
   }
  },
  "0xD074": {
   "name": "ND Filter Preset Select",
   "values": {
    "0x01": "Preset 1",
    "0x02": "Preset 2",
    "0x03": "Preset 3"
   }
  },
  "0xD075": {
   "name": "ND Filter Preset 1 Value",
   "values": {
    "0xFFFFFFFFFFFFFFFF": "nothing to display."
   }
  },
  "0xD076": {
   "name": "ND Filter Preset 2 Value",
   "values": {
    "0xFFFFFFFFFFFFFFFF": "nothing to display."
   }
  },
  "0xD077": {
   "name": "ND Filter Preset 3 Value",
   "values": {
    "0xFFFFFFFFFFFFFFFF": "nothing to display."
   }
  },
  "0xD078": {
   "name": "Monitoring Output Display SDI",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD079": {
   "name": "Monitoring Output Display HDMI",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD07A": {
   "name": "Camera System Error Info"
  },
  "0xD07B": {
   "name": "Lens Model Name"
  },
  "0xD07C": {
   "name": "Lens Serial Number"
  },
  "0xD07D": {
   "name": "Lens Version Number"
  },
  "0xD07F": {
   "name": "Simul Rec Setting (Movie Rec Button)",
   "values": {
    "0x01": "Rec Button1[SlotA SlotB]、Rec Button2[SlotA SlotB]",
    "0x02": "Rec Button1[SlotA]、Rec Button2[SlotB]",
    "0x03": "Rec Button1[SlotB]、Rec Button2[SlotA]"
   }
  },
  "0xD080": {
   "name": "Displayed Menu Status",
   "values": {
    "0x01": "Off",
    "0x02": "StatusMenu",
    "0x03": "FullMenu"
   }
  },
  "0xD085": {
   "name": "White Balance Switch",
   "values": {
    "0x01": "Preset Mode",
    "0x02": "Memory A Mode",
    "0x03": "Memory B Mode"
   }
  },
  "0xD086": {
   "name": "White Balance Preset Color Temperature"
  },
  "0xD087": {
   "name": "White Balance R Gain"
  },
  "0xD088": {
   "name": "White Balance B Gain"
  },
  "0xD089": {
   "name": "White Balance Offset Color Temp ATW"
  },
  "0xD08A": {
   "name": "White Balance Offset Tint ATW"
  },
  "0xD08B": {
   "name": "BaseLookImport Operation Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD08F": {
   "name": "Language Setting",
   "values": {
    "0x00": "Invalid",
    "0x01": "English",
    "0x02": "Japanese",
    "0x03": "Simplified Chinese",
    "0x04": "Traditional Chinese",
    "0x05": "Korean",
    "0x06": "Spanish",
    "0x07": "Russian",
    "0x08": "Brazilian Portuguese",
    "0x09": "Portuguese",
    "0x0A": "Indonesian",
    "0x0B": "French",
    "0x0C": "German",
    "0x0D": "Italian",
    "0x0E": "Dutch",
    "0x0F": "Greek",
    "0x10": "Arabic",
    "0x11": "Persian",
    "0x12": "Thai",
    "0x13": "Turkish",
    "0x14": "Polish",
    "0x15": "Czech",
    "0x16": "Hungarian",
    "0x17": "Malay",
    "0x18": "Hindi"
   }
  },
  "0xD091": {
   "name": "Auto Focus Hold",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD092": {
   "name": "Image ID (Numerical Value) Setting",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD099": {
   "name": "Exposure Ctrl Type",
   "values": {
    "0x01": "P/A/S/M Mode",
    "0x02": "Flexible Exp. Mode"
   }
  },
  "0xD09A": {
   "name": "FTPSettingList Operation Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD09C": {
   "name": "Playback Contents Recording Date/Time"
  },
  "0xD09D": {
   "name": "Playback Contents Name"
  },
  "0xD09E": {
   "name": "Playback Contents Number"
  },
  "0xD09F": {
   "name": "Playback Contents Total Number"
  },
  "0xD0A0": {
   "name": "Playback Contents Recording Resolution"
  },
  "0xD0A1": {
   "name": "Playback Contents Recording Frame Rate",
   "values": {
    "0x01": "120p (*1)",
    "0x02": "100p",
    "0x03": "60p (*2)",
    "0x04": "50p",
    "0x05": "30p (*3)",
    "0x06": "25p",
    "0x07": "24p (*4)",
    "0x08": "23.98p",
    "0x09": "29.97p",
    "0x0A": "59.94p",
    "0x0B": "19.98p",
    "0x0C": "14.99p",
    "0x0D": "12.50p",
    "0x0E": "12.00p",
    "0x0F": "11.99p",
    "0x10": "10.00p",
    "0x11": "9.99p",
    "0x12": "6.00p",
    "0x13": "5.99p",
    "0x14": "5.00p",
    "0x15": "4.995p",
    "0x16": "24.00p",
    "0x17": "119.88p",
    "0x41": "120i (*1)",
    "0x42": "100i",
    "0x43": "60i (*2)",
    "0x44": "50i",
    "0x45": "30i (*3)",
    "0x46": "25i",
    "0x47": "24i (*4)",
    "0x48": "23.98i",
    "0x49": "29.97i",
    "0x4A": "59.94i",
    "0x4B": "19.98i",
    "0x4C": "14.99i",
    "0x4D": "12.50i",
    "0x4E": "12.00i",
    "0x4F": "11.99i",
    "0x50": "10.00i",
    "0x51": "9.99i",
    "0x52": "6.00i",
    "0x53": "5.99i",
    "0x54": "5.00i",
    "0x55": "4.995i",
    "0x56": "24.00i",
    "0x57": "119.88i"
   }
  },
  "0xD0A2": {
   "name": "Playback Contents Recording File Format",
   "values": {
    "0x01": "DVD",
    "0x02": "M2PS",
    "0x03": "AVCHD",
    "0x04": "MP4",
    "0x05": "DV",
    "0x06": "XAVC",
    "0x07": "MXF",
    "0x08": "XAVC S 4K",
    "0x09": "XAVC S HD",
    "0x0A": "XAVC HS 8K",
    "0x0B": "XAVC HS 4K",
    "0x0C": "XAVC S-L 4K",
    "0x0D": "XAVC S-L HD",
    "0x0E": "XAVC S-I 4K",
    "0x0F": "XAVC S-I HD",
    "0x10": "XAVC I",
    "0x11": "XAVC L",
    "0x12": "XAVC Proxy",
    "0x13": "XAVC HS HD",
    "0x14": "XAVC S-I DCI 4K",
    "0x15": "XAVC H-I HQ",
    "0x16": "XAVC H-I SQ",
    "0x17": "XAVC H-L",
    "0x18": "X-OCN XT",
    "0x19": "X-OCN ST",
    "0x1A": "X-OCN LT",
    "0x1B": "XAVC HS-L 422",
    "0x1C": "XAVC HS-L 420",
    "0x1D": "XAVC S-L 422",
    "0x1E": "XAVC S-L 420",
    "0x1F": "XAVC S-I 422",
    "0x20": "MPEG HD 422"
   }
  },
  "0xD0A4": {
   "name": "Playback Contents Gamma Type",
   "values": {
    "0x0001": "S-Gamut3 / S-Log3",
    "0x0002": "S-Gamut3.Cine / S-Log3",
    "0x0003": "BT2020 / HLG",
    "0x0004": "BT2020 / S-Log3",
    "0xFFFF": "Other"
   }
  },
  "0xD0A9": {
   "name": "White Balance Offset Setting",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD0AA": {
   "name": "White Balance Offset Color Temp"
  },
  "0xD0AB": {
   "name": "Focus Bracket Shooting Status",
   "values": {
    "0x00": "Not Shooting",
    "0x01": "Shooting"
   }
  },
  "0xD0AC": {
   "name": "Video Stream Select",
   "values": {
    "0x01": "Video Stream 1",
    "0x02": "Video Stream 2",
    "0x03": "Video Stream 3",
    "0x04": "Video Stream 4",
    "0x05": "Video Stream 5",
    "0x06": "Video Stream 6",
    "0x07": "Video Stream 7",
    "0x08": "Video Stream 8",
    "0x09": "Video Stream 9",
    "0x0A": "Video Stream 10",
    "0x0B": "Video Stream 11",
    "0x0C": "Video Stream 12",
    "0x0D": "Video Stream 13",
    "0x0E": "Video Stream 14",
    "0x0F": "Video Stream 15",
    "0x10": "Video Stream 16"
   }
  },
  "0xD0AD": {
   "name": "Video Stream Codec",
   "values": {
    "0x0001": "OFF",
    "0x0002": "Motion Jpeg",
    "0x0003": "H.264",
    "0x0004": "H.265"
   }
  },
  "0xD0AE": {
   "name": "Video Stream Resolution"
  },
  "0xD0B5": {
   "name": "Video Stream Bit Rate Compression Mode",
   "values": {
    "0x01": "Constant Bit Rate (CBR)",
    "0x02": "Variable Bit Rate (VBR)"
   }
  },
  "0xD0BC": {
   "name": "Camera Operating Mode",
   "values": {
    "0x01": "Record",
    "0x02": "Playback"
   }
  },
  "0xD0BD": {
   "name": "Playback View Mode",
   "values": {
    "0x01": "Playback",
    "0x02": "Index"
   }
  },
  "0xD0BF": {
   "name": "Firmware Update Command Version"
  },
  "0xD0C0": {
   "name": "Firmware Update Status",
   "values": {
    "0x00": "Invalid",
    "0x01": "Off",
    "0x02": "Accepting data upload"
   }
  },
  "0xD0C1": {
   "name": "Maximum number of bytes"
  },
  "0xD0C3": {
   "name": "BaseLook Name of Playback"
  },
  "0xD0C4": {
   "name": "BaseLook Applied of Playback",
   "values": {
    "0x01": "Applicable",
    "0x02": "Not applicable"
   }
  },
  "0xD0C5": {
   "name": "Type-C Accessory Mode",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD0C6": {
   "name": "Pixel Mapping Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD0C7": {
   "name": "Delete UserBaseLook",
   "values": {
    "0x0000": "invalid",
    "0xFFFF": "All"
   }
  },
  "0xD0C8": {
   "name": "Select UserBaseLook to Edit",
   "values": {
    "0x0000": "invalid"
   }
  },
  "0xD0C9": {
   "name": "UserBaseLook Input",
   "values": {
    "0x01": "S-Gamut3/SLog3",
    "0x02": "S-Gamut3.Cine/SLog3"
   }
  },
  "0xD0CA": {
   "name": "UserBaseLook AE Level Offset"
  },
  "0xD0CB": {
   "name": "Base ISO Switch EI"
  },
  "0xD0CC": {
   "name": "Select BaseLook to Set in PPLUT",
   "values": {
    "0x0000": "invalid"
   }
  },
  "0xD0CD": {
   "name": "Eframing Scale (Auto)",
   "values": {
    "0x01": "Low",
    "0x02": "Mid",
    "0x03": "High"
   }
  },
  "0xD0CE": {
   "name": "Eframing Speed (Auto)"
  },
  "0xD0CF": {
   "name": "Camera Eframing",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD0D0": {
   "name": "S&Q Rec Frame Rate",
   "values": {
    "0x01": "120p (*1)",
    "0x02": "100p",
    "0x03": "60p (*2)",
    "0x04": "50p",
    "0x05": "30p (*3)",
    "0x06": "25p",
    "0x07": "24p (*4)",
    "0x08": "23.98p",
    "0x09": "29.97p",
    "0x0A": "59.94p",
    "0x0B": "19.98p",
    "0x0C": "14.99p",
    "0x0D": "12.50p",
    "0x0E": "12.00p",
    "0x0F": "11.99p",
    "0x10": "10.00p",
    "0x11": "9.99p",
    "0x12": "6.00p",
    "0x13": "5.99p",
    "0x14": "5.00p",
    "0x15": "4.995p",
    "0x16": "24.00p",
    "0x17": "119.88p",
    "0x41": "120i (*1)",
    "0x42": "100i",
    "0x43": "60i (*2)",
    "0x44": "50i",
    "0x45": "30i (*3)",
    "0x46": "25i",
    "0x47": "24i (*4)",
    "0x48": "23.98i",
    "0x49": "29.97i",
    "0x4A": "59.94i",
    "0x4B": "19.98i",
    "0x4C": "14.99i",
    "0x4D": "12.50i",
    "0x4E": "12.00i",
    "0x4F": "11.99i",
    "0x50": "10.00i",
    "0x51": "9.99i",
    "0x52": "6.00i",
    "0x53": "5.99i",
    "0x54": "5.00i",
    "0x55": "4.995i",
    "0x56": "24.00i",
    "0x57": "119.88i"
   }
  },
  "0xD0D1": {
   "name": "S&Q Record Setting",
   "values": {
    "0x0000": "Invalid",
    "0x0026": "600M 422 10bit",
    "0x0027": "500M 422 10bit",
    "0x0028": "400M 420 10bit",
    "0x0029": "300M 422 10bit",
    "0x002A": "280M 422 10bit",
    "0x002B": "250M 422 10bit",
    "0x002C": "240M 422 10bit",
    "0x002D": "222M 422 10bit",
    "0x002E": "200M 422 10bit",
    "0x002F": "200M 420 10bit",
    "0x0030": "200M 420 8bit",
    "0x0031": "185M 422 10bit",
    "0x0032": "150M 420 10bit",
    "0x0033": "150M 420 8bit",
    "0x0034": "140M 422 10bit",
    "0x0035": "111M 422 10bit",
    "0x0036": "100M 422 10bit",
    "0x0037": "100M 420 10bit",
    "0x0038": "100M 420 8bit",
    "0x0039": "93M 422 10bit",
    "0x003A": "89M 422 10bit",
    "0x003B": "75M 420 10bit",
    "0x003C": "60M 420 8bit",
    "0x003D": "50M 422 10bit",
    "0x003E": "50M 420 10bit",
    "0x003F": "50M 420 8bit",
    "0x0040": "45M 420 10bit",
    "0x0041": "30M 420 10bit",
    "0x0042": "25M 420 8bit",
    "0x0043": "16M 420 8bit",
    "0x0044": "520M 422 10bit",
    "0x0045": "260M 422 10bit"
   }
  },
  "0xD0D2": {
   "name": "Audio Recording",
   "values": {
    "0x00": "OFF",
    "0x01": "ON"
   }
  },
  "0xD0D3": {
   "name": "Time Code Preset"
  },
  "0xD0D4": {
   "name": "User Bit Preset"
  },
  "0xD0D5": {
   "name": "Time Code Format",
   "values": {
    "0x01": "DF",
    "0x02": "NDF"
   }
  },
  "0xD0D6": {
   "name": "Time Code Run",
   "values": {
    "0x01": "Rec Run",
    "0x02": "Free Run"
   }
  },
  "0xD0D7": {
   "name": "Time Code Make",
   "values": {
    "0x01": "Preset",
    "0x02": "Regenerate"
   }
  },
  "0xD0D8": {
   "name": "User Bit Time Rec",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD0D9": {
   "name": "Image Stabilization Steady Shot",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD0DA": {
   "name": "Image Stabilization Steady Shot (Movie)",
   "values": {
    "0x01": "OFF",
    "0x02": "Standard",
    "0x03": "Active",
    "0x04": "Hybrid"
   }
  },
  "0xD0DB": {
   "name": "Silent Mode",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD0DC": {
   "name": "Silent Mode Aperture Drive in AF",
   "values": {
    "0x01": "Not Target",
    "0x02": "Standard",
    "0x03": "Silent Priority"
   }
  },
  "0xD0DD": {
   "name": "Silent Mode Shutter When Power OFF",
   "values": {
    "0x01": "Not Target",
    "0x02": "OFF"
   }
  },
  "0xD0DE": {
   "name": "Silent Mode Auto Pixel Mapping",
   "values": {
    "0x01": "Not Target",
    "0x02": "OFF"
   }
  },
  "0xD0DF": {
   "name": "Shutter Type",
   "values": {
    "0x01": "Auto",
    "0x02": "Mechanical Shutter",
    "0x03": "Electronic Shutter"
   }
  },
  "0xD0E0": {
   "name": "Picture Profile BlackLevel"
  },
  "0xD0E1": {
   "name": "Picture Profile Gamma",
   "values": {
    "0x0001": "Movie",
    "0x0002": "Still",
    "0x0003": "S-Cinetone",
    "0x0101": "Cine1",
    "0x0102": "Cine2",
    "0x0103": "Cine3",
    "0x0104": "Cine4",
    "0x0201": "ITU709",
    "0x0202": "ITU709(800%)",
    "0x0302": "S-Log2",
    "0x0303": "S-Log3",
    "0x0401": "HLG",
    "0x0402": "HLG1",
    "0x0403": "HLG2",
    "0x0404": "HLG3"
   }
  },
  "0xD0E2": {
   "name": "Picture Profile BlackGamma Range",
   "values": {
    "0x01": "Wide",
    "0x02": "Middle",
    "0x03": "Narrow"
   }
  },
  "0xD0E3": {
   "name": "Picture Profile BlackGamma Level"
  },
  "0xD0E4": {
   "name": "Picture Profile Knee Mode",
   "values": {
    "0x01": "Auto",
    "0x02": "Manual"
   }
  },
  "0xD0E5": {
   "name": "Picture Profile Knee AutoSet MaxPoint",
   "values": {
    "0x0000": "invalid"
   }
  },
  "0xD0E6": {
   "name": "Picture Profile Knee AutoSet Sensitivity",
   "values": {
    "0x01": "Low",
    "0x02": "Mid",
    "0x03": "High"
   }
  },
  "0xD0E7": {
   "name": "Picture Profile Knee ManualSet Point",
   "values": {
    "0x0000": "invalid"
   }
  },
  "0xD0E8": {
   "name": "Picture Profile Knee ManualSet Slope"
  },
  "0xD0E9": {
   "name": "Picture Profile Color Mode",
   "values": {
    "0x0001": "Movie",
    "0x0002": "Still",
    "0x0003": "S-Cinetone",
    "0x0004": "Cinema",
    "0x0005": "Pro",
    "0x0006": "ITU709 Matrix",
    "0x0007": "Black&White",
    "0x0008": "S-Gamut3.Cine",
    "0x0009": "S-Gamut3",
    "0x000A": "BT.2020",
    "0x000B": "709",
    "0x000C": "S-Gamut",
    "0x000D": "709tone"
   }
  },
  "0xD0EA": {
   "name": "Picture Profile Saturation"
  },
  "0xD0EB": {
   "name": "Picture Profile ColorPhase"
  },
  "0xD0EC": {
   "name": "Picture Profile Color Depth Red"
  },
  "0xD0ED": {
   "name": "Picture Profile Color Depth Green"
  },
  "0xD0EE": {
   "name": "Picture Profile Color Depth Blue"
  },
  "0xD0EF": {
   "name": "Picture Profile Color Depth Cyan"
  },
  "0xD0F0": {
   "name": "Picture Profile Color Depth Magenta"
  },
  "0xD0F1": {
   "name": "Picture Profile Color Depth Yellow"
  },
  "0xD0F2": {
   "name": "Picture Profile Detail Level"
  },
  "0xD0F3": {
   "name": "Picture Profile Detail Adjust Mode",
   "values": {
    "0x01": "Auto",
    "0x02": "Manual"
   }
  },
  "0xD0F4": {
   "name": "Picture Profile Detail Adjust V/H Balance"
  },
  "0xD0F5": {
   "name": "Picture Profile Detail Adjust B/W Balance",
   "values": {
    "0x01": "Type1",
    "0x02": "Type2",
    "0x03": "Type3",
    "0x04": "Type4",
    "0x05": "Type5"
   }
  },
  "0xD0F6": {
   "name": "Picture Profile Detail Adjust Limit"
  },
  "0xD0F7": {
   "name": "Picture Profile Detail Adjust Crispening"
  },
  "0xD0F8": {
   "name": "Picture Profile Detail Adjust Highlight Detail"
  },
  "0xD0F9": {
   "name": "Copy Picture Profile",
   "values": {
    "0x01": "Picture Profile number 1",
    "0x02": "Picture Profile number 2",
    "0x03": "Picture Profile number 3",
    "0x04": "Picture Profile number 4",
    "0x05": "Picture Profile number 5",
    "0x06": "Picture Profile number 6",
    "0x07": "Picture Profile number 7",
    "0x08": "Picture Profile number 8",
    "0x09": "Picture Profile number 9",
    "0x0A": "Picture Profile number 10",
    "0x0B": "Picture Profile number 11"
   }
  },
  "0xD0FA": {
   "name": "Creative Look",
   "values": {
    "0x0001": "ST(Standard)",
    "0x0002": "PT(Portrait)",
    "0x0003": "NT(Neutral)",
    "0x0004": "VV(Vivid)",
    "0x0005": "VV2(Vivid2)",
    "0x0006": "FL(Film)",
    "0x0007": "IN(Instant)",
    "0x0008": "SH(Soft Highkey)",
    "0x0009": "BW(Black&White)",
    "0x000A": "SE(Sepia)",
    "0x000B": "FL2(Film2)",
    "0x000C": "FL3(Film3)",
    "0x0101": "Custom Look 1",
    "0x0102": "Custom Look 2",
    "0x0103": "Custom Look 3",
    "0x0104": "Custom Look 4",
    "0x0105": "Custom Look 5",
    "0x0106": "Custom Look 6"
   }
  },
  "0xD0FB": {
   "name": "Creative Look Contrast"
  },
  "0xD0FC": {
   "name": "Creative Look Highlights"
  },
  "0xD0FD": {
   "name": "Creative Look Shadows"
  },
  "0xD0FE": {
   "name": "Creative Look Fade"
  },
  "0xD0FF": {
   "name": "Creative Look Saturation"
  },
  "0xD100": {
   "name": "Creative Look Sharpness"
  },
  "0xD101": {
   "name": "Creative Look Sharpness Range"
  },
  "0xD102": {
   "name": "Creative Look Clarity"
  },
  "0xD103": {
   "name": "Custom Look Image Style",
   "values": {
    "0x0001": "ST(Standard)",
    "0x0002": "PT(Portrait)",
    "0x0003": "NT(Neutral)",
    "0x0004": "VV(Vivid)",
    "0x0005": "VV2(Vivid2)",
    "0x0006": "FL(Film)",
    "0x0007": "IN(Instant)",
    "0x0008": "SH(Soft Highkey)",
    "0x0009": "BW(Black&White)",
    "0x000A": "SE(Sepia)",
    "0x000B": "FL2(Film2)",
    "0x000C": "FL3(Film3)"
   }
  },
  "0xD104": {
   "name": "Time Code Preset Reset Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD105": {
   "name": "User Bit Preset Reset Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD106": {
   "name": "Sensor Cleaning Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD107": {
   "name": "Reset Picture Profile Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD108": {
   "name": "Reset Creative Look Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD109": {
   "name": "Proxy Record Setting",
   "values": {
    "0x0000": "Invalid",
    "0x0001": "16M 420 10bit",
    "0x0002": "9M 420 10bit",
    "0x0003": "6M 420 8bit",
    "0x0004": "9M 420 8bit"
   }
  },
  "0xD10E": {
   "name": "Video Stream Adaptive Rate Control",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD10F": {
   "name": "Video Stream Resolution Method",
   "values": {
    "0x01": "Manual mode",
    "0x02": "Easy mode"
   }
  },
  "0xD113": {
   "name": "Stream Cipher Type",
   "values": {
    "0x01": "None",
    "0x02": "AES_128",
    "0x03": "AES_192",
    "0x04": "AES_256"
   }
  },
  "0xD116": {
   "name": "Stream Latency"
  },
  "0xD117": {
   "name": "Stream TTL"
  },
  "0xD118": {
   "name": "Stream Mode Setting",
   "values": {
    "0x01": "Undefined",
    "0x02": "Caller",
    "0x03": "Listener",
    "0x04": "Rendezvous"
   }
  },
  "0xD119": {
   "name": "Stream Status",
   "values": {
    "0x01": "Inactive",
    "0x02": "Idle",
    "0x03": "Streaming",
    "0x04": "Error"
   }
  },
  "0xD11A": {
   "name": "Video Stream Movie Rec Permission",
   "values": {
    "0x01": "Not Permitted",
    "0x02": "Permitted"
   }
  },
  "0xD11B": {
   "name": "Audio Stream Codec Type",
   "values": {
    "0x20": "AAC-LC"
   }
  },
  "0xD11C": {
   "name": "Audio Stream Sampling Frequency",
   "values": {
    "0x12CA": "48.1KHz"
   }
  },
  "0xD11D": {
   "name": "Audio Stream Bit Depth",
   "values": {
    "0x01": "16bit",
    "0x02": "24bit",
    "0x03": "32bit"
   }
  },
  "0xD11E": {
   "name": "Audio Stream Channel",
   "values": {
    "0x01": "1ch",
    "0x02": "2ch",
    "0x04": "4ch"
   }
  },
  "0xD11F": {
   "name": "Interval REC (Movie) Count Down Interval Time"
  },
  "0xD120": {
   "name": "Recording Duration"
  },
  "0xD122": {
   "name": "Eframing Type",
   "values": {
    "0x01": "None",
    "0x02": "Auto",
    "0x03": "Single",
    "0x04": "Reserved",
    "0x05": "PTZ",
    "0x06": "Reserved",
    "0x07": "Reserved",
    "0x08": "Hold Current Position",
    "0x09": "Force Zoom Out"
   }
  },
  "0xD123": {
   "name": "Eframing Command Version"
  },
  "0xD124": {
   "name": "Eframing Mode (Auto)",
   "values": {
    "0x01": "Auto",
    "0x02": "Touch Kick",
    "0x03": "Time Sequence A",
    "0x04": "Time Sequence B"
   }
  },
  "0xD127": {
   "name": "Eframing Speed (PTZ)"
  },
  "0xD12D": {
   "name": "Second Battery Remaining"
  },
  "0xD12E": {
   "name": "Second Battery Level Indicator",
   "values": {
    "0x01": "Fake Battery",
    "0x02": "Unusable",
    "0x03": "Pre-End Battery",
    "0x04": "Battery Level 1/4",
    "0x05": "Battery Level 2/4",
    "0x06": "Battery Level 3/4",
    "0x07": "Battery Level 4/4",
    "0x08": "Battery Level 1/3",
    "0x09": "Battery Level 2/3",
    "0x0A": "Battery Level 3/3",
    "0x0B": "Pre-End Battery with USB Bus Power Supply",
    "0x0C": "Battery Level 1/4 with USB Bus Power Supply",
    "0x0D": "Battery Level 2/4 with USB Bus Power Supply",
    "0x0E": "Battery Level 3/4 with USB Bus Power Supply",
    "0x0F": "Battery Level 4/4 with USB Bus Power Supply",
    "0x10": "USB Bus Power Supply",
    "0xFF": "Battery Not Installed"
   }
  },
  "0xD12F": {
   "name": "ContShootSpotBoostSpeed"
  },
  "0xD130": {
   "name": "ContShootSpotBoost Status",
   "values": {
    "0x01": "Not SpotBoosting",
    "0x02": "SpotBoosting"
   }
  },
  "0xD131": {
   "name": "ContShootSpotBoost Enable Status",
   "values": {
    "0x01": "Disable",
    "0x02": "Enable"
   }
  },
  "0xD132": {
   "name": "Select IPTC Metadata",
   "values": {
    "0x00": "OFF"
   }
  },
  "0xD133": {
   "name": "Flicker Less Shooting",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD134": {
   "name": "Flicker Less Shooting Status",
   "values": {
    "0x00": "NotDetected",
    "0x01": "Detected"
   }
  },
  "0xD135": {
   "name": "Custom WB Size Setting",
   "values": {
    "0x01": "S",
    "0x02": "M",
    "0x03": "L"
   }
  },
  "0xD138": {
   "name": "AF Free Size and Position Setting"
  },
  "0xD139": {
   "name": "Paint/Look User Matrix Setting",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD13A": {
   "name": "Paint/Look User Matrix Level"
  },
  "0xD13B": {
   "name": "Paint/Look User Matrix Phase"
  },
  "0xD13C": {
   "name": "Paint/Look User Matrix R-G"
  },
  "0xD13D": {
   "name": "Paint/Look User Matrix R-B"
  },
  "0xD13E": {
   "name": "Paint/Look User Matrix G-R"
  },
  "0xD13F": {
   "name": "Paint/Look User Matrix G-B"
  },
  "0xD140": {
   "name": "Paint/Look User Matrix B-R"
  },
  "0xD141": {
   "name": "Paint/Look User Matrix B-G"
  },
  "0xD142": {
   "name": "Paint/Look Multi Matrix Setting",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD143": {
   "name": "Paint/Look Multi Matrix Axis",
   "values": {
    "0x0100": "B",
    "0x0101": "B+",
    "0x0180": "B-",
    "0x0200": "MG",
    "0x0201": "MG+",
    "0x0280": "MG-",
    "0x0300": "R",
    "0x0301": "R+",
    "0x0400": "YL",
    "0x0401": "YL+",
    "0x0480": "YL-",
    "0x0500": "G",
    "0x0501": "G+",
    "0x0580": "G-",
    "0x0600": "CY",
    "0x0601": "CY+"
   }
  },
  "0xD144": {
   "name": "Paint/Look Multi Matrix Hue"
  },
  "0xD145": {
   "name": "Paint/Look Multi Matrix Saturation"
  },
  "0xD14A": {
   "name": "FTP Transfer Still Quality Size",
   "values": {
    "0x01": "Small",
    "0x02": "Large"
   }
  },
  "0xD14B": {
   "name": "FTP Transfer Target (Proxy)",
   "values": {
    "0x01": "Proxy Only",
    "0x02": "Original Only",
    "0x03": "Proxy & Original"
   }
  },
  "0xD14C": {
   "name": "FTP Power Save",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD14D": {
   "name": "ISO Auto Min Shutter Speed Mode",
   "values": {
    "0x01": "Preset",
    "0x02": "Manual"
   }
  },
  "0xD14E": {
   "name": "ND Filter Unit Setting",
   "values": {
    "0x01": "Optical Density",
    "0x02": "Transmittance"
   }
  },
  "0xD14F": {
   "name": "ND Filter Optical Density Value",
   "values": {
    "0xFFFF": "nothing to display"
   }
  },
  "0xD150": {
   "name": "USB Power Supply",
   "values": {
    "0x01": "Off",
    "0x02": "On",
    "0x03": "Auto",
    "0x04": "Port1",
    "0x05": "Port2"
   }
  },
  "0xD151": {
   "name": "Interval REC (Movie) Frame Rate",
   "values": {
    "0x02": "100p",
    "0x04": "50p",
    "0x06": "25p",
    "0x08": "23.98p",
    "0x09": "29.97p",
    "0x0A": "59.94p",
    "0x16": "24.00p",
    "0x17": "119.88p"
   }
  },
  "0xD152": {
   "name": "Interval REC (Movie) Record Setting",
   "values": {
    "0x0000": "Invalid",
    "0x0026": "600M 422 10bit",
    "0x0027": "500M 422 10bit",
    "0x0028": "400M 420 10bit",
    "0x0029": "300M 422 10bit",
    "0x002A": "280M 422 10bit",
    "0x002B": "250M 422 10bit",
    "0x002C": "240M 422 10bit",
    "0x002D": "222M 422 10bit",
    "0x002E": "200M 422 10bit",
    "0x002F": "200M 420 10bit",
    "0x0030": "200M 420 8bit",
    "0x0031": "185M 422 10bit",
    "0x0032": "150M 420 10bit",
    "0x0033": "150M 420 8bit",
    "0x0034": "140M 422 10bit",
    "0x0035": "111M 422 10bit",
    "0x0036": "100M 422 10bit",
    "0x0037": "100M 420 10bit",
    "0x0038": "100M 420 8bit",
    "0x0039": "93M 422 10bit",
    "0x003A": "89M 422 10bit",
    "0x003B": "75M 420 10bit",
    "0x003C": "60M 420 8bit",
    "0x003D": "50M 422 10bit",
    "0x003E": "50M 420 10bit",
    "0x003F": "50M 420 8bit",
    "0x0040": "45M 420 10bit",
    "0x0041": "30M 420 10bit",
    "0x0042": "25M 420 8bit",
    "0x0043": "16M 420 8bit",
    "0x0044": "520M 422 10bit",
    "0x0045": "260M 422 10bit"
   }
  },
  "0xD153": {
   "name": "Eframing Recording Image Crop",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD154": {
   "name": "Eframing HDMI Crop",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD155": {
   "name": "Synchroterminal Forced Output",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD156": {
   "name": "Shutter Release Time Lag Control",
   "values": {
    "0x01": "Off",
    "0x02": "On:Stability",
    "0x03": "On:Fastest"
   }
  },
  "0xD157": {
   "name": "Subject Recognition in AF",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD158": {
   "name": "Recognition Target",
   "values": {
    "0x0001": "Person",
    "0x0002": "Animal + Bird",
    "0x0003": "Animal",
    "0x0004": "Bird",
    "0x0005": "Insect",
    "0x0006": "Car/Train",
    "0x0007": "Plane",
    "0xFFFF": "Auto"
   }
  },
  "0xD159": {
   "name": "Right/Left Eye Select",
   "values": {
    "0x01": "Auto",
    "0x02": "Right Eye",
    "0x03": "Left Eye"
   }
  },
  "0xD15B": {
   "name": "Long Exposure NR",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD15C": {
   "name": "High ISO NR",
   "values": {
    "0x01": "OFF",
    "0x02": "Low",
    "0x03": "Normal",
    "0x04": "High"
   }
  },
  "0xD15D": {
   "name": "HLG Still Image",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD15E": {
   "name": "Color Space (Still Image)",
   "values": {
    "0x01": "sRGB",
    "0x02": "AdobeRGB"
   }
  },
  "0xD15F": {
   "name": "Recording Media (Still Image)",
   "values": {
    "0x0001": "Slot1",
    "0x0002": "Slot2",
    "0x0101": "Simultaneous recording",
    "0x0102": "Sort Recording"
   }
  },
  "0xD160": {
   "name": "Recording Media (Movie)",
   "values": {
    "0x0001": "Slot 1",
    "0x0002": "Slot 2",
    "0x0101": "Simultaneous recording"
   }
  },
  "0xD161": {
   "name": "Auto Switch Media",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD162": {
   "name": "Continuous Shooting Speed in Electric Shutter(Hi+)",
   "values": {
    "0x0000": "Invalid"
   }
  },
  "0xD163": {
   "name": "Continuous Shooting Speed in Electric Shutter(Hi)",
   "values": {
    "0x0000": "Invalid"
   }
  },
  "0xD164": {
   "name": "Continuous Shooting Speed in Electric Shutter(Mid)",
   "values": {
    "0x0000": "Invalid"
   }
  },
  "0xD165": {
   "name": "Continuous Shooting Speed in Electric Shutter(Lo)",
   "values": {
    "0x0000": "Invalid"
   }
  },
  "0xD166": {
   "name": "Bracket order",
   "values": {
    "0x01": "0→-→+",
    "0x02": "-→0→+"
   }
  },
  "0xD167": {
   "name": "Focus Bracket order",
   "values": {
    "0x01": "0→-→+",
    "0x02": "0→+"
   }
  },
  "0xD168": {
   "name": "Focus Bracket Exposure Lock 1st Img",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD169": {
   "name": "Focus Bracket Interval Until Next Shot",
   "values": {
    "0x0000": "Invalid",
    "0xFFFF": "Shortest Interval"
   }
  },
  "0xD16A": {
   "name": "Interval REC (Still) Shooting Start Time"
  },
  "0xD16B": {
   "name": "Interval REC (Still) Shooting Interval"
  },
  "0xD16C": {
   "name": "Interval REC (Still) Number of Shots"
  },
  "0xD16D": {
   "name": "Interval REC (Still) AE Tracking Sensitivity",
   "values": {
    "0x01": "OFF",
    "0x02": "Low",
    "0x03": "Mid",
    "0x04": "High"
   }
  },
  "0xD16E": {
   "name": "Interval REC (Still) Shutter Type",
   "values": {
    "0x01": "Auto",
    "0x02": "Mechanical Shutter",
    "0x03": "Electronic Shutter"
   }
  },
  "0xD16F": {
   "name": "Interval REC (Still) Shoot Interval Priority",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD170": {
   "name": "e-Front Curtain Shutter",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD171": {
   "name": "Wind Noise Reduct",
   "values": {
    "0x01": "OFF",
    "0x02": "ON",
    "0x03": "AUTO"
   }
  },
  "0xD172": {
   "name": "Audio Level Display",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD173": {
   "name": "Auto Slow Shutter",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD176": {
   "name": "ISO Auto Min Shutter Speed Manual",
   "values": {
    "0x0000000000000000": "Invalid"
   }
  },
  "0xD177": {
   "name": "ISO Auto Min Shutter Speed Preset",
   "values": {
    "0x01": "Slower",
    "0x02": "Slow",
    "0x03": "Standard",
    "0x04": "Fast",
    "0x05": "Faster"
   }
  },
  "0xD178": {
   "name": "Soft Skin Effect",
   "values": {
    "0x01": "OFF",
    "0x02": "Low",
    "0x03": "Mid",
    "0x04": "High"
   }
  },
  "0xD179": {
   "name": "Priority Set in AF-S",
   "values": {
    "0x01": "AF",
    "0x02": "Release",
    "0x03": "Balanced Emphasis"
   }
  },
  "0xD17A": {
   "name": "Priority Set in AF-C",
   "values": {
    "0x01": "AF",
    "0x02": "Release",
    "0x03": "Balanced Emphasis"
   }
  },
  "0xD17B": {
   "name": "Focus Magnification Time",
   "values": {
    "0x02": "2seconds",
    "0x05": "5seconds",
    "0xFF": "No Limit"
   }
  },
  "0xD17C": {
   "name": "Playback Volume Settings"
  },
  "0xD17D": {
   "name": "Auto Review",
   "values": {
    "0x00": "Off"
   }
  },
  "0xD17E": {
   "name": "Audio Signals",
   "values": {
    "0x01": "OFF",
    "0x02": "ON",
    "0x03": "ON:Shutter Only",
    "0x04": "ON:W/O Shutter"
   }
  },
  "0xD17F": {
   "name": "HDMI Resolution (Still/Play)",
   "values": {
    "0x0001": "4320p/2160p",
    "0x0002": "2160p",
    "0x0003": "2160p/1080p",
    "0x0004": "1080p",
    "0x0005": "720p",
    "0x0006": "480p",
    "0x0007": "576p",
    "0x0104": "1080i",
    "0x0106": "480i",
    "0x0107": "576i",
    "0xFFFF": "Auto"
   }
  },
  "0xD180": {
   "name": "HDMI Output Rec Media (Movie)",
   "values": {
    "0x01": "Off (HDMI Only)",
    "0x02": "On"
   }
  },
  "0xD181": {
   "name": "HDMI Output Resolution (Movie)",
   "values": {
    "0x0001": "4320p/2160p",
    "0x0002": "2160p",
    "0x0003": "2160p/1080p",
    "0x0004": "1080p",
    "0x0005": "720p",
    "0x0006": "480p",
    "0x0007": "576p",
    "0x0104": "1080i",
    "0x0106": "480i",
    "0x0107": "576i",
    "0xFFFF": "Auto"
   }
  },
  "0xD182": {
   "name": "HDMI Output 4K Set (Movie)",
   "values": {
    "0x0001": "59.94p 10bit",
    "0x0002": "50.00p 10bit",
    "0x0003": "29.97p 10bit",
    "0x0004": "25.00p 10bit",
    "0x0005": "24.00p 10bit",
    "0x0006": "23.98p 10bit",
    "0x0101": "59.94p 8bit",
    "0x0102": "50.00p 8bit",
    "0x0103": "29.97p 8bit",
    "0x0104": "25.00p 8bit",
    "0x0106": "23.98p 8bit"
   }
  },
  "0xD183": {
   "name": "HDMI Output RAW (Movie)",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD184": {
   "name": "HDMI Output Raw Setting (Movie)",
   "values": {
    "0x01": "59.94p",
    "0x02": "50.00p",
    "0x03": "29.97p",
    "0x04": "25.00p",
    "0x05": "24.00p",
    "0x06": "23.98p"
   }
  },
  "0xD185": {
   "name": "HDMI Output Color Gamut for RAW Out (Movie)",
   "values": {
    "0x01": "S-Gamut3 Cine/S-Log3",
    "0x02": "S-Gamut3/S-Log3"
   }
  },
  "0xD186": {
   "name": "HDMI Output Time Code (Movie)",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD187": {
   "name": "HDMI Output REC Control (Movie)",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD189": {
   "name": "TimeShift Shooting",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD18A": {
   "name": "TimeShift Trigger Setting",
   "values": {
    "0x01": "S1+AF",
    "0x02": "S1",
    "0x03": "AF"
   }
  },
  "0xD18B": {
   "name": "TimeShift PreShooting Time Setting"
  },
  "0xD18C": {
   "name": "Top of the Group Shooting Mark Setting",
   "values": {
    "0x0000": "None",
    "0x0001": "Rating1",
    "0x0002": "Rating2",
    "0x0003": "Rating3",
    "0x0004": "Rating4",
    "0x0005": "Rating5",
    "0x0101": "Divider Frame"
   }
  },
  "0xD18E": {
   "name": "Media SLOT3 Status",
   "values": {
    "0x01": "OK",
    "0x02": "No card",
    "0x03": "Card error",
    "0x04": "Card recognizing/Card locked and DB error",
    "0x05": "DB error",
    "0x06": "Card recognizing",
    "0x07": "Card locked and DB error",
    "0x08": "DB error (can't repair and need format)",
    "0x09": "Card error (Read Only Media)"
   }
  },
  "0xD18F": {
   "name": "Media SLOT3 Remaining shooting time"
  },
  "0xD190": {
   "name": "Media SLOT3 Rec Available Type",
   "values": {
    "0x00": "None",
    "0x01": "Main",
    "0x02": "Proxy",
    "0x03": "Main + Proxy"
   }
  },
  "0xD191": {
   "name": "Media SLOT3 ProfileUrl"
  },
  "0xD192": {
   "name": "Image Stabilization Steady Shot Adjust",
   "values": {
    "0x01": "Automatic",
    "0x02": "Manual"
   }
  },
  "0xD193": {
   "name": "Image Stabilization Steady Shot Focal Length",
   "values": {
    "0x0008": "8mm",
    "0x0009": "9mm",
    "0x000A": "10mm",
    "0x000B": "11mm",
    "0x000C": "12mm",
    "0x000D": "13mm",
    "0x000E": "14mm",
    "0x000F": "15mm",
    "0x0010": "16mm",
    "0x0011": "17mm",
    "0x0012": "18mm",
    "0x0013": "19mm",
    "0x0014": "20mm",
    "0x0015": "21mm",
    "0x0018": "24mm",
    "0x0019": "25mm",
    "0x001C": "28mm",
    "0x001E": "30mm",
    "0x0020": "32mm",
    "0x0023": "35mm",
    "0x0028": "40mm",
    "0x002D": "45mm",
    "0x0032": "50mm",
    "0x0037": "55mm",
    "0x003C": "60mm",
    "0x0046": "70mm",
    "0x004B": "75mm",
    "0x0050": "80mm",
    "0x0055": "85mm",
    "0x005A": "90mm",
    "0x0064": "100mm",
    "0x0069": "105mm",
    "0x0078": "120mm",
    "0x0087": "135mm",
    "0x0096": "150mm",
    "0x00B4": "180mm",
    "0x00C8": "200mm",
    "0x00D2": "210mm",
    "0x00FA": "250mm",
    "0x012C": "300mm",
    "0x015E": "350mm",
    "0x0190": "400mm",
    "0x01C2": "450mm",
    "0x01F4": "500mm",
    "0x0258": "600mm",
    "0x0320": "800mm",
    "0x03E8": "1000mm"
   }
  },
  "0xD194": {
   "name": "Camera Shake Status",
   "values": {
    "0x01": "No Error",
    "0x02": "Error"
   }
  },
  "0xD195": {
   "name": "Update Body Status",
   "values": {
    "0x0001": "No error",
    "0x0101": "Other errors",
    "0x0102": "No update body file in media",
    "0x0103": "File version older",
    "0x0104": "File damaged",
    "0x0105": "File invalid data",
    "0x0106": "File model not match",
    "0x0107": "File region not match",
    "0x0108": "File version not match",
    "0x0109": "Low battery",
    "0x010A": "Unsupported battery"
   }
  },
  "0xD196": {
   "name": "Embed LUT File",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD197": {
   "name": "Media SLOT1 Writing State",
   "values": {
    "0x01": "Not writing",
    "0x02": "Contents writing"
   }
  },
  "0xD198": {
   "name": "Media SLOT2 Writing State",
   "values": {
    "0x01": "Not writing",
    "0x02": "Contents writing"
   }
  },
  "0xD199": {
   "name": "Auto FTP Transfer Target (Movie)",
   "values": {
    "0x01": "All",
    "0x02": "Only Shot Mark",
    "0x03": "Protect Only"
   }
  },
  "0xD19A": {
   "name": "FTP Transfer Target",
   "values": {
    "0x01": "JPEG/HEIF Only",
    "0x02": "RAW Only",
    "0x03": "RAW & JPEG/HEIF"
   }
  },
  "0xD19B": {
   "name": "TimeShift Shooting Status",
   "values": {
    "0x01": "Idle",
    "0x02": "TimeShiftShooting",
    "0x03": "Impossible",
    "0x04": "Impossible (TimerShiftOverheating)",
    "0x05": "MainShooting"
   }
  },
  "0xD19C": {
   "name": "Focus Driving Status (Absolute)",
   "values": {
    "0x01": "Not Driving",
    "0x02": "Driving"
   }
  },
  "0xD19D": {
   "name": "Zoom Driving Status (Absolute)",
   "values": {
    "0x01": "Not Driving",
    "0x02": "Driving"
   }
  },
  "0xD19E": {
   "name": "Default AF Free Size and Position Setting"
  },
  "0xD19F": {
   "name": "Extended Shutter Speed",
   "values": {
    "0x0000000000000000": "BULB",
    "0xFFFFFFFFFFFFFFFF": "nothing to display"
   }
  },
  "0xD1A2": {
   "name": "Lens Compensation Shading",
   "values": {
    "0x01": "OFF",
    "0x02": "Auto",
    "0x03": "Low"
   }
  },
  "0xD1A3": {
   "name": "Lens Compensation Chromatic Aberration",
   "values": {
    "0x01": "OFF",
    "0x02": "Auto"
   }
  },
  "0xD1A4": {
   "name": "Lens Compensation Distortion",
   "values": {
    "0x01": "OFF",
    "0x02": "Auto"
   }
  },
  "0xD1A5": {
   "name": "Lens Compensation Breathing",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1A6": {
   "name": "Focus Bracket Recording Folder",
   "values": {
    "0x01": "Current Folder",
    "0x02": "New Folder"
   }
  },
  "0xD1A7": {
   "name": "Release without Lens",
   "values": {
    "0x01": "Disable",
    "0x02": "Enable"
   }
  },
  "0xD1A8": {
   "name": "Release without Card",
   "values": {
    "0x01": "Disable",
    "0x02": "Enable"
   }
  },
  "0xD1A9": {
   "name": "Image Stabilization Framing Stabilizer",
   "values": {
    "0x01": "OFF",
    "0x02": "ON:Manual",
    "0x03": "ON:Center"
   }
  },
  "0xD1AA": {
   "name": "Priority Set in AWB",
   "values": {
    "0x01": "Standard",
    "0x02": "Ambience",
    "0x03": "White"
   }
  },
  "0xD1AB": {
   "name": "AF Illuminator",
   "values": {
    "0x01": "OFF",
    "0x02": "Auto"
   }
  },
  "0xD1AC": {
   "name": "Aperture Drive in AF",
   "values": {
    "0x01": "Standard",
    "0x02": "Focus Priority",
    "0x03": "Silent Priority"
   }
  },
  "0xD1AD": {
   "name": "AF with Shutter",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1AE": {
   "name": "Full Time DMF",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1AF": {
   "name": "Pre-AF",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1B0": {
   "name": "Display Quality (Finder/Monitor)",
   "values": {
    "0x01": "Standard",
    "0x02": "High"
   }
  },
  "0xD1B1": {
   "name": "Audio Signals Volume"
  },
  "0xD1B2": {
   "name": "Control for HDMI",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1B3": {
   "name": "Anti-dust Shutter When Power Off",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1B4": {
   "name": "Shooting Self-timer Status",
   "values": {
    "0x01": "Ready",
    "0x02": "Counting down"
   }
  },
  "0xD1B5": {
   "name": "Metered Manual Level"
  },
  "0xD1B6": {
   "name": "ISO Auto Range Limit (min)"
  },
  "0xD1B7": {
   "name": "ISO Auto Range Limit (max)"
  },
  "0xD1B8": {
   "name": "Face/Eye Frame Display",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1BA": {
   "name": "AF in Focus Magnifier",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1BB": {
   "name": "Camera Error Caution Status",
   "values": {
    "0x01": "No Error",
    "0x02": "Error"
   }
  },
  "0xD1BC": {
   "name": "System Error Caution Status",
   "values": {
    "0x01": "No Error",
    "0x02": "Error"
   }
  },
  "0xD1BD": {
   "name": "Initial Focus Magnifier (Still)"
  },
  "0xD1BE": {
   "name": "Remote Save Image Size",
   "values": {
    "0x01": "Large Size",
    "0x02": "Small Size"
   }
  },
  "0xD1BF": {
   "name": "Program Shift Status",
   "values": {
    "0x01": "Idle",
    "0x02": "Program Shift"
   }
  },
  "0xD1C1": {
   "name": "Wake on LAN",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1C6": {
   "name": "Tracking On + AF On Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD1C7": {
   "name": "APS-C/Super 35mm Shooting",
   "values": {
    "0x01": "OFF",
    "0x02": "ON",
    "0x03": "Auto"
   }
  },
  "0xD1C8": {
   "name": "Recording File Number (Still)",
   "values": {
    "0x01": "Reset",
    "0x02": "Series"
   }
  },
  "0xD1C9": {
   "name": "Forced File Number Reset Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD1CA": {
   "name": "Recording Setting File Name"
  },
  "0xD1CB": {
   "name": "Recording Folder Format",
   "values": {
    "0x01": "Standard Format",
    "0x02": "Date Format"
   }
  },
  "0xD1CC": {
   "name": "StreamSettingList Operation Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD1CD": {
   "name": "Write Copyright Info",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1CE": {
   "name": "Set Photographer"
  },
  "0xD1CF": {
   "name": "Set Copyright"
  },
  "0xD1D0": {
   "name": "File Settings Camera ID",
   "values": {
    "0x01": "A",
    "0x02": "B",
    "0x03": "C",
    "0x04": "D",
    "0x05": "E",
    "0x06": "F",
    "0x07": "G",
    "0x08": "H",
    "0x09": "I",
    "0x0A": "J",
    "0x0B": "K",
    "0x0C": "L",
    "0x0D": "M",
    "0x0E": "N",
    "0x0F": "O",
    "0x10": "P",
    "0x11": "Q",
    "0x12": "R",
    "0x13": "S",
    "0x14": "T",
    "0x15": "U",
    "0x16": "V",
    "0x17": "W",
    "0x18": "X",
    "0x19": "Y",
    "0x1A": "Z"
   }
  },
  "0xD1D1": {
   "name": "File Settings Reel Number"
  },
  "0xD1D2": {
   "name": "File Settings Camera Position",
   "values": {
    "0x01": "Center",
    "0x02": "Left",
    "0x03": "Right"
   }
  },
  "0xD1D4": {
   "name": "ContentInfoList Enable Status (SLOT1)",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD1D5": {
   "name": "ContentInfoList Enable Status (SLOT2)",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD1D6": {
   "name": "ContentInfoList regenerate time (SLOT1)"
  },
  "0xD1D7": {
   "name": "ContentInfoList regenerate time (SLOT2)"
  },
  "0xD1DA": {
   "name": "PostView Transfer Resource Status",
   "values": {
    "0x01": "available",
    "0x02": "unavailable"
   }
  },
  "0xD1DB": {
   "name": "Create New Folder (Still) Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD1DC": {
   "name": "File Settings Title Name Settings"
  },
  "0xD1DD": {
   "name": "Microphone directivity",
   "values": {
    "0x01": "Auto",
    "0x02": "All",
    "0x03": "Front",
    "0x04": "Rear"
   }
  },
  "0xD1DE": {
   "name": "Grid Line Display",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1DF": {
   "name": "Product Showcase Set",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1E0": {
   "name": "Amount of Defocus Setting"
  },
  "0xD1E1": {
   "name": "Cinematic Vlog Setting",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1E2": {
   "name": "Cinematic Vlog Look",
   "values": {
    "0x0001": "Classic",
    "0x0002": "S-cinetone",
    "0x0003": "Clean",
    "0x0004": "Chic",
    "0x0005": "Fresh",
    "0x0006": "Mono",
    "0x0101": "User1",
    "0x0102": "User2",
    "0x0103": "User3",
    "0x0104": "User4",
    "0x0105": "User5",
    "0x0106": "User6",
    "0x0107": "User7",
    "0x0108": "User8",
    "0x0109": "User9",
    "0x010A": "User10",
    "0x010B": "User11",
    "0x010C": "User12",
    "0x010D": "User13",
    "0x010E": "User14",
    "0x010F": "User15",
    "0x0110": "User16"
   }
  },
  "0xD1E3": {
   "name": "Cinematic Vlog Mood",
   "values": {
    "0x01": "Auto",
    "0x02": "Gold",
    "0x03": "Ocean",
    "0x04": "Forest"
   }
  },
  "0xD1E4": {
   "name": "Cinematic Vlog AF Transition Speed",
   "values": {
    "0x01": "High",
    "0x02": "Middle",
    "0x03": "Low"
   }
  },
  "0xD1E5": {
   "name": "Face Priority in Multi Metering",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1E6": {
   "name": "Subject Recognition Person Tracking Subject Shift Range"
  },
  "0xD1E7": {
   "name": "Subject Recognition Animal+Bird Priority",
   "values": {
    "0x01": "Auto",
    "0x02": "Animal Priority",
    "0x03": "Bird Priority"
   }
  },
  "0xD1E8": {
   "name": "Subject Recognition Animal+Bird Detection Parts",
   "values": {
    "0x01": "Eye+Head+Body",
    "0x02": "Eye+Head",
    "0x03": "Eye",
    "0xFF": "Follow individual Setting"
   }
  },
  "0xD1E9": {
   "name": "Subject Recognition Animal (Still) Tracking Subject Shift Range"
  },
  "0xD1EA": {
   "name": "Subject Recognition Animal (Still) Tracking Sensitivity"
  },
  "0xD1EB": {
   "name": "Subject Recognition Animal (Still) Detection Sensitivity"
  },
  "0xD1EC": {
   "name": "Subject Recognition Animal (Still) Detection Parts",
   "values": {
    "0x01": "Eye+Head+Body",
    "0x02": "Eye+Head",
    "0x03": "Eye"
   }
  },
  "0xD1ED": {
   "name": "Subject Recognition Bird Tracking Subject Shift Range"
  },
  "0xD1EE": {
   "name": "Subject Recognition Bird Tracking Sensitivity"
  },
  "0xD1EF": {
   "name": "Subject Recognition Bird Detection Sensitivity"
  },
  "0xD1F0": {
   "name": "Subject Recognition Bird Detection Parts",
   "values": {
    "0x01": "Eye+Head+Body",
    "0x02": "Eye+Head",
    "0x03": "Eye"
   }
  },
  "0xD1F1": {
   "name": "Subject Recognition Insect Tracking Subject Shift Range"
  },
  "0xD1F2": {
   "name": "Subject Recognition Insect Tracking Sensitivity"
  },
  "0xD1F3": {
   "name": "Subject Recognition Insect Detection Sensitivity"
  },
  "0xD1F4": {
   "name": "Subject Recognition Car/Train Tracking Subject Shift Range"
  },
  "0xD1F5": {
   "name": "Subject Recognition Car/Train Tracking Sensitivity"
  },
  "0xD1F6": {
   "name": "Subject Recognition Car/Train Detection Sensitivity"
  },
  "0xD1F7": {
   "name": "Subject Recognition Plane Tracking Subject Shift Range"
  },
  "0xD1F8": {
   "name": "Subject Recognition Plane Tracking Sensitivity"
  },
  "0xD1F9": {
   "name": "Subject Recognition Plane Detection Sensitivity"
  },
  "0xD1FA": {
   "name": "Subject Recognition Priority on registered face",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1FB": {
   "name": "Monitor Brightness Type",
   "values": {
    "0x01": "Manual",
    "0x02": "Sunny Wheather"
   }
  },
  "0xD1FC": {
   "name": "Monitor Brightness Manual"
  },
  "0xD1FD": {
   "name": "TC/UB Display Setting",
   "values": {
    "0x01": "Counter",
    "0x02": "TC",
    "0x03": "U-Bit",
    "0x04": "Duration"
   }
  },
  "0xD1FE": {
   "name": "Gamma Display Assist",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD1FF": {
   "name": "Gamma Display Assist Type",
   "values": {
    "0x0001": "Auto",
    "0x0302": "S-Log2→709 (800%)",
    "0x0303": "S-Log3→709 (800%)",
    "0x0401": "HLG (BT.2020)",
    "0x0402": "HLG (709)"
   }
  },
  "0xD200": {
   "name": "Flash Compensation"
  },
  "0xD201": {
   "name": "Dynamic Range Optimizer",
   "values": {
    "0x01": "DRO OFF",
    "0x02": "DRO",
    "0x10": "DRO+",
    "0x11": "DRO + Manual1",
    "0x12": "DRO + Manual2",
    "0x13": "DRO + Manual3",
    "0x14": "DRO + Manual4",
    "0x15": "DRO + Manual5",
    "0x1F": "DRO AUTO",
    "0x20": "HDR AUTO",
    "0x21": "HDR 1.0Ev",
    "0x22": "HDR 2.0Ev",
    "0x23": "HDR 3.0Ev",
    "0x24": "HDR 4.0Ev",
    "0x25": "HDR 5.0Ev",
    "0x26": "HDR 6.0Ev"
   }
  },
  "0xD203": {
   "name": "Image Size",
   "values": {
    "0x01": "L",
    "0x02": "M",
    "0x03": "S",
    "0x04": "24M",
    "0x05": "16M",
    "0x06": "13M",
    "0x07": "11M",
    "0x08": "8.4M",
    "0x09": "6.1M",
    "0x0A": "6M",
    "0x0B": "5.6M",
    "0x0C": "4M",
    "0x0D": "2.6M",
    "0x0E": "21M",
    "0x0F": "20M",
    "0x10": "10M",
    "0x11": "9.2M",
    "0x12": "8.7M",
    "0x13": "6.9M",
    "0x14": "4.6M",
    "0x15": "4.1M",
    "0x16": "4M",
    "0x17": "3.9M",
    "0x18": "3.1M",
    "0x19": "VGA"
   }
  },
  "0xD204": {
   "name": "Total Battery Remaining"
  },
  "0xD205": {
   "name": "Total Battery Level Indicator",
   "values": {
    "0x01": "Fake Battery",
    "0x02": "Unusable",
    "0x03": "Pre-End Battery",
    "0x04": "Battery Level 1/4",
    "0x05": "Battery Level 2/4",
    "0x06": "Battery Level 3/4",
    "0x07": "Battery Level 4/4",
    "0x08": "Battery Level 1/3",
    "0x09": "Battery Level 2/3",
    "0x0A": "Battery Level 3/3",
    "0x0B": "Pre-End Battery with USB Bus Power Supply",
    "0x0C": "Battery Level 1/4 with USB Bus Power Supply",
    "0x0D": "Battery Level 2/4 with USB Bus Power Supply",
    "0x0E": "Battery Level 3/4 with USB Bus Power Supply",
    "0x0F": "Battery Level 4/4 with USB Bus Power Supply",
    "0x10": "USB Bus Power Supply",
    "0xFF": "Battery Not Installed"
   }
  },
  "0xD207": {
   "name": "OSD Image Mode",
   "values": {
    "0x00": "OFF",
    "0x01": "ON"
   }
  },
  "0xD208": {
   "name": "Camera Button Function capability",
   "values": {
    "0x0001": "Up button",
    "0x0002": "Down button",
    "0x0003": "Left button",
    "0x0004": "Right button",
    "0x0005": "ENTER button",
    "0x0006": "MENU button",
    "0x0007": "Multi-selector Up",
    "0x0008": "Multi-selector Down",
    "0x0009": "Multi-selector Left",
    "0x000A": "Multi-selector Right",
    "0x000B": "Multi-selector Enter",
    "0x000C": "Multi-selector Right Up",
    "0x000D": "Multi-selector Right Down",
    "0x000E": "Multi-selector Left Up",
    "0x000F": "Multi-selector Left Down",
    "0x0010": "Fn button",
    "0x0011": "Playback button",
    "0x0012": "Delete button",
    "0x0013": "Mode button",
    "0x0014": "C1 button",
    "0x0015": "C2 button",
    "0x0016": "C3 button",
    "0x0017": "C4 button",
    "0x0018": "C5 button",
    "0x0019": "C6 button",
    "0x001A": "MOVIE button",
    "0x001B": "AEL button",
    "0x001C": "AF-ON button",
    "0x001D": "Home Button",
    "0x001E": "Clips Button",
    "0x001F": "Slot-Select Button",
    "0x0020": "Display Button",
    "0x0021": "C7 button",
    "0x0022": "Cancel/Back Button",
    "0x0023": "Thumbnail Button"
   }
  },
  "0xD209": {
   "name": "Camera Button Function Multi capability"
  },
  "0xD20A": {
   "name": "Camera Dial Function capability",
   "values": {
    "0x4001": "Control wheel",
    "0x4002": "Front dial",
    "0x4003": "Rear dial L",
    "0x4004": "Rear dial R"
   }
  },
  "0xD20B": {
   "name": "Camera Lever Function capability",
   "values": {
    "0x5001": "Tele/Wide Lever"
   }
  },
  "0xD20C": {
   "name": "Camera Button Function Status",
   "values": {
    "0x0001": "Idle",
    "0x0002": "Any Key On"
   }
  },
  "0xD20D": {
   "name": "Shutter Speed",
   "values": {
    "0x00000000": "BULB",
    "0xFFFFFFFF": "nothing to display"
   }
  },
  "0xD20E": {
   "name": "Battery Level Indicator",
   "values": {
    "0x01": "Fake Battery",
    "0x02": "Unusable",
    "0x03": "Pre-End Battery",
    "0x04": "Battery Level 1/4",
    "0x05": "Battery Level 2/4",
    "0x06": "Battery Level 3/4",
    "0x07": "Battery Level 4/4",
    "0x08": "Battery Level 1/3",
    "0x09": "Battery Level 2/3",
    "0x0A": "Battery Level 3/3",
    "0x0B": "Pre-End Battery with USB Bus Power Supply",
    "0x0C": "Battery Level 1/4 with USB Bus Power Supply",
    "0x0D": "Battery Level 2/4 with USB Bus Power Supply",
    "0x0E": "Battery Level 3/4 with USB Bus Power Supply",
    "0x0F": "Battery Level 4/4 with USB Bus Power Supply",
    "0x10": "USB Bus Power Supply",
    "0xFF": "Battery Not Installed"
   }
  },
  "0xD20F": {
   "name": "Color Temperature"
  },
  "0xD210": {
   "name": "Biaxial Fine-Tuning G-M Direction"
  },
  "0xD211": {
   "name": "Aspect Ratio",
   "values": {
    "0x01": "3:02",
    "0x02": "16:09",
    "0x03": "4:03",
    "0x04": "1:01"
   }
  },
  "0xD213": {
   "name": "Focus Indication",
   "values": {
    "0x01": "Unlock",
    "0x02": "●[AF-S]Focused, and AF Locked State",
    "0x03": "●[AF-S]Not Focused, and Low Contrast State",
    "0x04": "Not Used",
    "0x05": "(( ))[AF-C]Tracking Subject motion",
    "0x06": "((●))[AF-C]Focused State",
    "0x07": "((●)[AF-C]Not Focused, and Low Contrast State",
    "0x08": "Unpause",
    "0x09": "Pause"
   }
  },
  "0xD214": {
   "name": "Predicted Maximum File Size"
  },
  "0xD215": {
   "name": "Shooting File Info"
  },
  "0xD216": {
   "name": "Auto FTP Transfer Target (Still)",
   "values": {
    "0x01": "All",
    "0x02": "Protect Only"
   }
  },
  "0xD217": {
   "name": "AELock Indication",
   "values": {
    "0x01": "Unlocked",
    "0x02": "Locked"
   }
  },
  "0xD218": {
   "name": "Battery Remaining"
  },
  "0xD21B": {
   "name": "Picture Effect",
   "values": {
    "0x8000": "OFF",
    "0x8001": "Toy Camera Normal",
    "0x8002": "Toy Camera Cool",
    "0x8003": "Toy Camera Warm",
    "0x8004": "Toy Camera Green",
    "0x8005": "Toy Camera Magenta",
    "0x8010": "Pop Color",
    "0x8020": "Posterization B/W",
    "0x8021": "Posterization Color",
    "0x8030": "Retro Photo",
    "0x8040": "Soft High-key",
    "0x8050": "Partial Color Red",
    "0x8051": "Partial Color Green",
    "0x8052": "Partial Color Blue",
    "0x8053": "Partial Color Yellow",
    "0x8060": "High Contrast Mono",
    "0x8070": "Soft Focus Low",
    "0x8071": "Soft Focus Mid",
    "0x8072": "Soft Focus High",
    "0x8080": "HDR Painting Low",
    "0x8081": "HDR Painting Mid",
    "0x8082": "HDR Painting High",
    "0x8090": "Rich-tone Mono",
    "0x80A0": "Miniature Auto",
    "0x80A1": "Miniature Top",
    "0x80A2": "Miniature Middle(Horizontal",
    "0x80A3": "Miniature Bottom",
    "0x80A4": "Miniature Right",
    "0x80A5": "Miniature Middle(Vertical)",
    "0x80A6": "Miniature Left",
    "0x80B0": "Miniature Wator Color",
    "0x80C0": "Miniature Illustration Low",
    "0x80C1": "Miniature Illustration Mid",
    "0x80C2": "Miniature Illustration High"
   }
  },
  "0xD21C": {
   "name": "Biaxial Fine-Tuning A-B Direction"
  },
  "0xD21D": {
   "name": "Movie Recording State",
   "values": {
    "0x00": "Not Recording",
    "0x01": "Recording",
    "0x02": "Recording Failed",
    "0x03": "Waiting Record"
   }
  },
  "0xD21E": {
   "name": "ISO Sensitivity",
   "values": {
    "0x0000000A": "ISO 10",
    "0x0000000C": "ISO 12",
    "0x00000010": "ISO 16",
    "0x00000014": "ISO 20",
    "0x00000019": "ISO 25",
    "0x00000020": "ISO 32",
    "0x00000028": "ISO 40",
    "0x00000032": "ISO 50",
    "0x00000040": "ISO 64",
    "0x00000050": "ISO 80",
    "0x00000064": "ISO 100",
    "0x0000007D": "ISO 125",
    "0x000000A0": "ISO 160",
    "0x000000C8": "ISO 200",
    "0x000000FA": "ISO 250",
    "0x00000140": "ISO 320",
    "0x00000190": "ISO 400",
    "0x000001F4": "ISO 500",
    "0x00000280": "ISO 640",
    "0x00000320": "ISO 800",
    "0x000003E8": "ISO 1000",
    "0x000004E2": "ISO 1250",
    "0x00000640": "ISO 1600",
    "0x000007D0": "ISO 2000",
    "0x000009C4": "ISO 2500",
    "0x00000C80": "ISO 3200",
    "0x00000FA0": "ISO 4000",
    "0x00001388": "ISO 5000",
    "0x00001900": "ISO 6400",
    "0x00001F40": "ISO 8000",
    "0x00002710": "ISO 10000",
    "0x00003200": "ISO 12800",
    "0x00003E80": "ISO 16000",
    "0x00004E20": "ISO 20000",
    "0x00006400": "ISO 25600",
    "0x00007D00": "ISO 32000",
    "0x00009C40": "ISO 40000",
    "0x0000C800": "ISO 51200",
    "0x0000FA00": "ISO 64000",
    "0x00013880": "ISO 80000",
    "0x00019000": "ISO 102400",
    "0x0001F400": "ISO 128000",
    "0x00027100": "ISO 160000",
    "0x00032000": "ISO 204800",
    "0x0003E800": "ISO 256000",
    "0x0004E200": "ISO 320000",
    "0x00064000": "ISO 409600",
    "0x0007D000": "ISO 512000",
    "0x0009C400": "ISO 640000",
    "0x000C8000": "ISO 819200",
    "0x00FFFFFF": "ISO AUTO",
    "0x0100000A": "Multi Frame NR ISO 10",
    "0x0100000C": "Multi Frame NR ISO 12",
    "0x01000010": "Multi Frame NR ISO 16",
    "0x01000014": "Multi Frame NR ISO 20",
    "0x01000019": "Multi Frame NR ISO 25",
    "0x01000020": "Multi Frame NR ISO 32",
    "0x01000028": "Multi Frame NR ISO 40",
    "0x01000032": "Multi Frame NR ISO 50",
    "0x01000040": "Multi Frame NR ISO 64",
    "0x01000050": "Multi Frame NR ISO 80",
    "0x01000064": "Multi Frame NR ISO 100",
    "0x0100007D": "Multi Frame NR ISO 125",
    "0x010000A0": "Multi Frame NR ISO 160",
    "0x010000C8": "Multi Frame NR ISO 200",
    "0x010000FA": "Multi Frame NR ISO 250",
    "0x01000140": "Multi Frame NR ISO 320",
    "0x01000190": "Multi Frame NR ISO 400",
    "0x010001F4": "Multi Frame NR ISO 500",
    "0x01000280": "Multi Frame NR ISO 640",
    "0x01000320": "Multi Frame NR ISO 800",
    "0x010003E8": "Multi Frame NR ISO 1000",
    "0x010004E2": "Multi Frame NR ISO 1250",
    "0x01000640": "Multi Frame NR ISO 1600",
    "0x010007D0": "Multi Frame NR ISO 2000",
    "0x010009C4": "Multi Frame NR ISO 2500",
    "0x01000C80": "Multi Frame NR ISO 3200",
    "0x01000FA0": "Multi Frame NR ISO 4000",
    "0x01001388": "Multi Frame NR ISO 5000",
    "0x01001900": "Multi Frame NR ISO 6400",
    "0x01001F40": "Multi Frame NR ISO 8000",
    "0x01002710": "Multi Frame NR ISO 10000",
    "0x01003200": "Multi Frame NR ISO 12800",
    "0x01003E80": "Multi Frame NR ISO 16000",
    "0x01006400": "Multi Frame NR ISO 25600",
    "0x0100C800": "Multi Frame NR ISO 51200",
    "0x01019000": "Multi Frame NR ISO 102400",
    "0x01032000": "Multi Frame NR ISO 204800",
    "0x01064000": "Multi Frame NR ISO 409600",
    "0x010C8000": "Multi Frame NR ISO 819200",
    "0x01FFFFFF": "Multi Frame NR ISO AUTO",
    "0x0200000A": "Multi Frame NR High ISO 10",
    "0x0200000C": "Multi Frame NR High ISO 12",
    "0x02000010": "Multi Frame NR High ISO 16",
    "0x02000014": "Multi Frame NR High ISO 20",
    "0x02000019": "Multi Frame NR High ISO 25",
    "0x02000020": "Multi Frame NR High ISO 32",
    "0x02000028": "Multi Frame NR High ISO 40",
    "0x02000032": "Multi Frame NR High ISO 50",
    "0x02000040": "Multi Frame NR High ISO 64",
    "0x02000050": "Multi Frame NR High ISO 80",
    "0x02000064": "Multi Frame NR High ISO 100",
    "0x0200007D": "Multi Frame NR High ISO 125",
    "0x020000A0": "Multi Frame NR High ISO 160",
    "0x020000C8": "Multi Frame NR High ISO 200",
    "0x020000FA": "Multi Frame NR High ISO 250",
    "0x02000140": "Multi Frame NR High ISO 320",
    "0x02000190": "Multi Frame NR High ISO 400",
    "0x020001F4": "Multi Frame NR High ISO 500",
    "0x02000280": "Multi Frame NR High ISO 640",
    "0x02000320": "Multi Frame NR High ISO 800",
    "0x020003E8": "Multi Frame NR High ISO 1000",
    "0x020004E2": "Multi Frame NR High ISO 1250",
    "0x02000640": "Multi Frame NR High ISO 1600",
    "0x020007D0": "Multi Frame NR High ISO 2000",
    "0x020009C4": "Multi Frame NR High ISO 2500",
    "0x02000C80": "Multi Frame NR High ISO 3200",
    "0x02000FA0": "Multi Frame NR High ISO 4000",
    "0x02001388": "Multi Frame NR High ISO 5000",
    "0x02001900": "Multi Frame NR High ISO 6400",
    "0x02001F40": "Multi Frame NR High ISO 8000",
    "0x02002710": "Multi Frame NR High ISO 10000",
    "0x02003200": "Multi Frame NR High ISO 12800",
    "0x02003E80": "Multi Frame NR High ISO 16000",
    "0x02006400": "Multi Frame NR High ISO 25600",
    "0x0200C800": "Multi Frame NR High ISO 51200",
    "0x02019000": "Multi Frame NR High ISO 102400",
    "0x02032000": "Multi Frame NR High ISO 204800",
    "0x02064000": "Multi Frame NR High ISO 409600",
    "0x020C8000": "Multi Frame NR High ISO 819200",
    "0x02FFFFFF": "Multi Frame NR High ISO AUTO"
   }
  },
  "0xD21F": {
   "name": "FELock Indication",
   "values": {
    "0x01": "Unlocked",
    "0x02": "Locked"
   }
  },
  "0xD220": {
   "name": "Audio Signals (Start/End)",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD221": {
   "name": "Live View Status",
   "values": {
    "0x00": "Live View Supported but Disabled: If this value is set, the host should not get the LiveView Image.",
    "0x01": "Live View Supported and Enabled: The host can get the LiveView Image and activate LiveView button if have.",
    "0x02": "Live View Not Supported: Just definition, if the camera doesn't support Liveview, the host can't get this property by any operation."
   }
  },
  "0xD222": {
   "name": "Still Image Save Destination",
   "values": {
    "0x0001": "Host Device (e.g., PC)",
    "0x0010": "Camera (Memory Card)",
    "0x0011": "Host Device & Camera (Memory Card)"
   }
  },
  "0xD223": {
   "name": "Date/Time Setting"
  },
  "0xD225": {
   "name": "Protect Image in FTP Transfer",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD229": {
   "name": "Auto Recognition Target Candidates",
   "values": {
    "0x00000001": "Person",
    "0x00000002": "Animal",
    "0x00000004": "Bird",
    "0x00000008": "Insect",
    "0x00000010": "Car/Train",
    "0x00000020": "Plane"
   }
  },
  "0xD22C": {
   "name": "Focus Area",
   "values": {
    "0x0000": "Unknown",
    "0x0001": "Wide",
    "0x0002": "Zone",
    "0x0003": "Center",
    "0x0101": "Flexible Spot S",
    "0x0102": "Flexible Spot M",
    "0x0103": "Flexible Spot L",
    "0x0104": "Expand Flexible Spot",
    "0x0105": "Flexible Spot",
    "0x0106": "Flexible Spot XS",
    "0x0107": "Flexible Spot XL",
    "0x0201": "Lock on AF Wide",
    "0x0202": "Lock on AF Zone",
    "0x0203": "Lock on AF Center",
    "0x0204": "Lock on AF Flexible Spot S",
    "0x0205": "Lock on AF Flexible Spot M",
    "0x0206": "Lock on AF Flexible Spot L",
    "0x0207": "Lock on Expand Flexible Spot",
    "0x0208": "Lock on AF Flexible Spot",
    "0x0209": "Lock on AF Flexible Spot XS",
    "0x020A": "Lock on AF Flexible Spot XL",
    "0x1101": "Flexible Spot Free Size 1",
    "0x1102": "Flexible Spot Free Size 2",
    "0x1103": "Flexible Spot Free Size 3",
    "0x1201": "Lock on AF Flexible Spot Free Size 1",
    "0x1202": "Lock on AF Flexible Spot Free Size 2",
    "0x1203": "Lock on AF Flexible Spot Free Size 3"
   }
  },
  "0xD231": {
   "name": "Live View Display Effect",
   "values": {
    "0x00": "Unknown",
    "0x01": "Setting Effect ON",
    "0x02": "Setting Effect OFF"
   }
  },
  "0xD234": {
   "name": "Auto Recognition Target Setting"
  },
  "0xD235": {
   "name": "Near/Far Enable Status",
   "values": {
    "0x00": "Disabled",
    "0x01": "Enabled"
   }
  },
  "0xD237": {
   "name": "Exposure Step",
   "values": {
    "0x0000": "Invalid"
   }
  },
  "0xD239": {
   "name": "Pixel Shift Shooting Mode",
   "values": {
    "0x00": "Off",
    "0x01": "Burst Shooting"
   }
  },
  "0xD23A": {
   "name": "Pixel Shift Shooting Number",
   "values": {
    "0x0001": "1 Sheet",
    "0x0004": "4 Sheets",
    "0x0010": "16 Sheets"
   }
  },
  "0xD23B": {
   "name": "Pixel Shift Shooting Interval",
   "values": {
    "0x0000": "0 sec.",
    "0x0001": "1 sec.",
    "0x0002": "2 sec.",
    "0x0003": "3 sec.",
    "0x0004": "4 sec.",
    "0x0005": "5 sec.",
    "0x000A": "10 sec.",
    "0x000F": "15 sec.",
    "0x001E": "30 sec.",
    "0xFFFF": "Shortest Interval"
   }
  },
  "0xD23C": {
   "name": "Pixel Shift Shooting Status",
   "values": {
    "0x00": "Not Shooting",
    "0x01": "Shooting"
   }
  },
  "0xD23D": {
   "name": "Progress Number of Pixel Shift Shooting",
   "values": {
    "0x0001": "1st shooting",
    "0x0002": "2nd shooting",
    "0x0003": "3rd shooting",
    "0x0004": "4th shooting",
    "0x0005": "5th shooting",
    "0x0006": "6th shooting",
    "0x0007": "7th shooting",
    "0x0008": "8th shooting",
    "0x0009": "9th shooting",
    "0x000A": "10th shooting",
    "0x000B": "11th shooting",
    "0x000C": "12th shooting",
    "0x000D": "13th shooting",
    "0x000E": "14th shooting",
    "0x000F": "15th shooting",
    "0x0010": "16th shooting"
   }
  },
  "0xD23F": {
   "name": "Picture Profile",
   "values": {
    "0x00": "Picture Profile OFF",
    "0x01": "Picture Profile number 1",
    "0x02": "Picture Profile number 2",
    "0x03": "Picture Profile number 3",
    "0x04": "Picture Profile number 4",
    "0x05": "Picture Profile number 5",
    "0x06": "Picture Profile number 6",
    "0x07": "Picture Profile number 7",
    "0x08": "Picture Profile number 8",
    "0x09": "Picture Profile number 9",
    "0x0A": "Picture Profile number 10",
    "0x0B": "Picture Profile number 11",
    "0x41": "Picture Profile LUT number 1",
    "0x42": "Picture Profile LUT number 2",
    "0x43": "Picture Profile LUT number 3",
    "0x44": "Picture Profile LUT number 4"
   }
  },
  "0xD240": {
   "name": "Creative Style",
   "values": {
    "0x01": "Standard",
    "0x02": "Vivid",
    "0x03": "Portrait",
    "0x04": "Landscape",
    "0x05": "Sunset",
    "0x06": "B/W (Black & White)",
    "0x07": "Light",
    "0x08": "Neutral",
    "0x09": "Clear",
    "0x0A": "Deep",
    "0x0B": "Night View",
    "0x0C": "Autumn Leaves",
    "0x0D": "Sepia",
    "0x0E": "Creative BOX1",
    "0x0F": "Creative BOX2",
    "0x10": "Creative BOX3",
    "0x11": "Creative BOX4",
    "0x12": "Creative BOX5",
    "0x13": "Creative BOX6"
   }
  },
  "0xD241": {
   "name": "File Format (Movie)",
   "values": {
    "0x01": "DVD",
    "0x02": "M2PS",
    "0x03": "AVCHD",
    "0x04": "MP4",
    "0x05": "DV",
    "0x06": "XAVC",
    "0x07": "MXF",
    "0x08": "XAVC S 4K",
    "0x09": "XAVC S HD",
    "0x0A": "XAVC HS 8K",
    "0x0B": "XAVC HS 4K",
    "0x0C": "XAVC S-L 4K",
    "0x0D": "XAVC S-L HD",
    "0x0E": "XAVC S-I 4K",
    "0x0F": "XAVC S-I HD",
    "0x10": "XAVC I",
    "0x11": "XAVC L",
    "0x12": "XAVC Proxy",
    "0x13": "XAVC HS HD",
    "0x14": "XAVC S-I DCI 4K",
    "0x15": "XAVC H-I HQ",
    "0x16": "XAVC H-I SQ",
    "0x17": "XAVC H-L",
    "0x18": "X-OCN XT",
    "0x19": "X-OCN ST",
    "0x1A": "X-OCN LT",
    "0x1B": "XAVC HS-L 422",
    "0x1C": "XAVC HS-L 420",
    "0x1D": "XAVC S-L 422",
    "0x1E": "XAVC S-L 420",
    "0x1F": "XAVC S-I 422",
    "0x20": "MPEG HD 422"
   }
  },
  "0xD242": {
   "name": "Recording Setting (Movie)",
   "values": {
    "0x0001": "60p 50M / XAVC S",
    "0x0002": "30p 50M / XAVC S",
    "0x0003": "24p 50M / XAVC S",
    "0x0004": "50p 50M / XAVC S",
    "0x0005": "25p 50M / XAVC S",
    "0x0006": "60i 24M(FX) / AVCHD",
    "0x0007": "50i 24M(FX) / AVCHD",
    "0x0008": "60i 17M(FH) / AVCHD",
    "0x0009": "50i 17M(FH) / AVCHD",
    "0x000A": "60p 28M(PS) / AVCHD",
    "0x000B": "50p 28M(PS) / AVCHD",
    "0x000C": "24p 24M(FX) / AVCHD",
    "0x000D": "25p 24M(FX) / AVCHD",
    "0x000E": "24p 17M(FH) / AVCHD",
    "0x000F": "25p 17M(FH) / AVCHD",
    "0x0010": "120p 50M (1280x720) / XAVC S",
    "0x0011": "100p 50M (1280x720) / XAVC S",
    "0x0012": "1920x1080 30p 16M / MP4",
    "0x0013": "1920x1080 25p 16M / MP4",
    "0x0014": "1280x720 30p 6M / MP4",
    "0x0015": "1280x720 25p 6M / MP4",
    "0x0016": "1920x1080 60p 28M / MP4",
    "0x0017": "1920x1080 50p 28M / MP4",
    "0x0018": "60p 25M / XAVC S HD",
    "0x0019": "50p 25M / XAVC S HD",
    "0x001A": "30p 16M / XAVC S HD",
    "0x001B": "25p 16M / XAVC S HD",
    "0x001C": "120p 100M (1920x1080) / XAVC S HD",
    "0x001D": "100p 100M (1920x1080) / XAVC S HD",
    "0x001E": "120p 60M (1920x1080) / XAVC S HD",
    "0x001F": "100p 60M (1920x1080) / XAVC S HD",
    "0x0020": "30p 100M / XAVC S 4K",
    "0x0021": "25p 100M / XAVC S 4K",
    "0x0022": "24p 100M / XAVC S 4K",
    "0x0023": "30p 60M / XAVC S 4K",
    "0x0024": "25p 60M / XAVC S 4K",
    "0x0025": "24p 60M / XAVC S 4K",
    "0x0026": "600M 422 10bit",
    "0x0027": "500M 422 10bit",
    "0x0028": "400M 420 10bit",
    "0x0029": "300M 422 10bit",
    "0x002A": "280M 422 10bit",
    "0x002B": "250M 422 10bit",
    "0x002C": "240M 422 10bit",
    "0x002D": "222M 422 10bit",
    "0x002E": "200M 422 10bit",
    "0x002F": "200M 420 10bit",
    "0x0030": "200M 420 8bit",
    "0x0031": "185M 422 10bit",
    "0x0032": "150M 420 10bit",
    "0x0033": "150M 420 8bit",
    "0x0034": "140M 422 10bit",
    "0x0035": "111M 422 10bit",
    "0x0036": "100M 422 10bit",
    "0x0037": "100M 420 10bit",
    "0x0038": "100M 420 8bit",
    "0x0039": "93M 422 10bit",
    "0x003A": "89M 422 10bit",
    "0x003B": "75M 420 10bit",
    "0x003C": "60M 420 8bit",
    "0x003D": "50M 422 10bit",
    "0x003E": "50M 420 10bit",
    "0x003F": "50M 420 8bit",
    "0x0040": "45M 420 10bit",
    "0x0041": "30M 420 10bit",
    "0x0042": "25M 420 8bit",
    "0x0043": "16M 420 8bit",
    "0x0044": "520M 422 10bit",
    "0x0045": "260M 422 10bit"
   }
  },
  "0xD248": {
   "name": "Media SLOT1 Status",
   "values": {
    "0x01": "OK",
    "0x02": "No Card",
    "0x03": "Card Error",
    "0x04": "Card Recognizing / Card Locked and DB Error",
    "0x05": "DB Error",
    "0x06": "Card Recognizing",
    "0x07": "Card Locked and DB Error",
    "0x08": "DB Error (Cannot Repair and Need Format)",
    "0x09": "Card Error (Read-Only Media)"
   }
  },
  "0xD249": {
   "name": "Media SLOT1 Remaining number shots"
  },
  "0xD24A": {
   "name": "Media SLOT1 Remaining shooting time"
  },
  "0xD24C": {
   "name": "Focal position"
  },
  "0xD24E": {
   "name": "AWBLock Indication",
   "values": {
    "0x01": "Unlocked",
    "0x02": "Locked"
   }
  },
  "0xD24F": {
   "name": "Interval REC (Still) Mode",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD250": {
   "name": "Interval REC (Still) Status",
   "values": {
    "0x00": "Waiting Start",
    "0x01": "Interval Shooting"
   }
  },
  "0xD251": {
   "name": "Device Overheating State"
  },
  "0xD252": {
   "name": "Still Image Quality",
   "values": {
    "0x01": "Extra Fine",
    "0x02": "Fine",
    "0x03": "Standard",
    "0x04": "Light"
   }
  },
  "0xD253": {
   "name": "File Format (Still)",
   "values": {
    "0x01": "RAW",
    "0x02": "RAW+JPEG",
    "0x03": "JPEG",
    "0x04": "RAW+HEIF",
    "0x05": "HEIF"
   }
  },
  "0xD254": {
   "name": "Focus Magnifier Setting"
  },
  "0xD255": {
   "name": "AF Tracking Sensitivity (Still)",
   "values": {
    "0x01": "1 (Locked on)",
    "0x02": "2",
    "0x03": "3 (Standard)",
    "0x04": "4",
    "0x05": "5 (Responsive)"
   }
  },
  "0xD256": {
   "name": "Media SLOT2 Status",
   "values": {
    "0x01": "OK",
    "0x02": "No Card",
    "0x03": "Card Error",
    "0x04": "Card Recognizing / Card Locked and DB Error",
    "0x05": "DB Error",
    "0x06": "Card Recognizing",
    "0x07": "Card Locked and DB Error",
    "0x08": "DB Error (Cannot Repair and Need Format)",
    "0x09": "Card Error (Read-Only Media)"
   }
  },
  "0xD257": {
   "name": "Media SLOT2 Remaining number shots"
  },
  "0xD258": {
   "name": "Media SLOT2 Remaining shooting time"
  },
  "0xD25A": {
   "name": "Position Key Setting",
   "values": {
    "0x00": "Camera position priority",
    "0x01": "PC Remote setting priority"
   }
  },
  "0xD25B": {
   "name": "Zoom Operation Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD25C": {
   "name": "Zoom Scale"
  },
  "0xD25D": {
   "name": "Zoom Bar Information"
  },
  "0xD25E": {
   "name": "Zoom Speed Range"
  },
  "0xD25F": {
   "name": "Zoom Setting",
   "values": {
    "0x01": "Optical zoom only",
    "0x02": "Smart zoom only",
    "0x03": "On: Clear Image Zoom",
    "0x04": "On: Digital Zoom"
   }
  },
  "0xD260": {
   "name": "Zoom Type Status",
   "values": {
    "0x01": "Optical Zoom",
    "0x02": "Smart Zoom",
    "0x03": "Clear Image Zoom",
    "0x04": "Digital Zoom"
   }
  },
  "0xD262": {
   "name": "Wireless Flash Setting",
   "values": {
    "0x00": "off",
    "0x01": "on"
   }
  },
  "0xD263": {
   "name": "Red Eye Reduction",
   "values": {
    "0x00": "off",
    "0x01": "on"
   }
  },
  "0xD264": {
   "name": "Remote Control Restriction Status",
   "values": {
    "0x00": "Disabled (No restrictions)",
    "0x01": "Enabled (Not support mode)"
   }
  },
  "0xD267": {
   "name": "Live View Area (x, y)"
  },
  "0xD268": {
   "name": "Still Image Trans Size",
   "values": {
    "0x01": "Original",
    "0x02": "Small Size JPEG"
   }
  },
  "0xD269": {
   "name": "RAW+J PC Save Image",
   "values": {
    "0x01": "RAW & JPEG",
    "0x02": "JPEG Only",
    "0x03": "RAW Only",
    "0x04": "RAW & HEIF",
    "0x05": "HEIF Only"
   }
  },
  "0xD26A": {
   "name": "Live View Image Quality",
   "values": {
    "0x01": "Low",
    "0x02": "High"
   }
  },
  "0xD26B": {
   "name": "Custom WB Capturable Area (x, y)"
  },
  "0xD26C": {
   "name": "Custom WB Capture Frame Size (x, y)"
  },
  "0xD26D": {
   "name": "Custom WB Capture Standby Operation",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD26E": {
   "name": "Custom WB Capture Standby Cancel Operation",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD26F": {
   "name": "Custom WB Capture Operation",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD270": {
   "name": "Custom WB Execution State",
   "values": {
    "0x00": "Invalid",
    "0x01": "Standby",
    "0x02": "Capturing",
    "0x03": "OperatingCamera"
   }
  },
  "0xD271": {
   "name": "Camera-Setting Save Operation",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD272": {
   "name": "Camera-Setting Read Operation",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD273": {
   "name": "Camera-Setting Save/Read State",
   "values": {
    "0x00": "Idle",
    "0x01": "Reading"
   }
  },
  "0xD274": {
   "name": "FTP-Setting Save Operation",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD275": {
   "name": "FTP-Setting Read Operation",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD276": {
   "name": "FTP-Setting Save/Read State",
   "values": {
    "0x00": "Idle",
    "0x01": "Reading"
   }
  },
  "0xD278": {
   "name": "LiveViewURL"
  },
  "0xD279": {
   "name": "Media SLOT1 Format Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD27A": {
   "name": "Media SLOT2 Format Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD27B": {
   "name": "Media Format Progress Rate",
   "values": {
    "0x00000000": "Invalid"
   }
  },
  "0xD27C": {
   "name": "Select FTP Server",
   "values": {
    "0x01": "FTP Server 1",
    "0x02": "FTP Server 2",
    "0x03": "FTP Server 3",
    "0x04": "FTP Server 4",
    "0x05": "FTP Server 5",
    "0x06": "FTP Server 6",
    "0x07": "FTP Server 7",
    "0x08": "FTP Server 8",
    "0x09": "FTP Server 9"
   }
  },
  "0xD27F": {
   "name": "FTP Connection Status",
   "values": {
    "0x01": "Connecting",
    "0x02": "Connected",
    "0x03": "Connected (Server certification error)",
    "0x04": "Connection Error"
   }
  },
  "0xD280": {
   "name": "FTP Connection Error Info",
   "values": {
    "0x0000": "No Error",
    "0x0001": "Camera system error",
    "0x0002": "Wi-Fi hardware error",
    "0x0003": "Wired LAN hardware error",
    "0x0004": "AP is not registered",
    "0x0005": "AP is not found(with connection retry)",
    "0x0006": "AP connection error",
    "0x0007": "AP password error",
    "0x0008": "Invalid key or static IP address setting error(WEP)",
    "0x0009": "Invalid key or IP address acquisition error(WEP)",
    "0x000A": "IP address acquisition error(DHCP)",
    "0x000B": "IP address acquisition error(DNS)",
    "0x000C": "Airplane mode is on",
    "0x000D": "LAN cable error(with connection retry)",
    "0x000E": "FTP server setting is not set",
    "0x000F": "FTP server registration setting error(User name or password error)",
    "0x0010": "FTP server is Severed",
    "0x0011": "Certificate verification is not normal",
    "0x0012": "Directory create error",
    "0x0013": "Authority error related to file handling / FTP server is over capacity",
    "0x0014": "Can't recognize USB-LAN conv Adapter",
    "0x0015": "Can't recognize the USB tethering device",
    "0x0016": "Check the device set of connect Device",
    "0x0017": "Failed to connect to server:Reconnecting",
    "0x0018": "Can't transfer to FTP server:Reconnecting",
    "0xFFFF": "Unknown errors"
   }
  },
  "0xD281": {
   "name": "High Resolution SS Setting",
   "values": {
    "0x00": "OFF",
    "0x01": "ON"
   }
  },
  "0xD282": {
   "name": "High Resolution Shutter Speed"
  },
  "0xD283": {
   "name": "Function of Touch Operation",
   "values": {
    "0x01": "OFF",
    "0x02": "Touch Shutter",
    "0x03": "Touch Focus",
    "0x04": "Touch Tracking",
    "0x05": "Touch AE",
    "0x06": "Touch Shutter + Touch AE ON",
    "0x07": "Touch Shutter + Touch AE OFF",
    "0x08": "Touch Focus + Touch AE ON",
    "0x09": "Touch Focus + Touch AE OFF",
    "0x0A": "Touch Tracking + Touch AE ON",
    "0x0B": "Touch Tracking + Touch AE OFF"
   }
  },
  "0xD284": {
   "name": "Remote Touch Operation Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD285": {
   "name": "Cancel Remote Touch Operation Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD286": {
   "name": "Recording Frame Rate Setting (Movie)",
   "values": {
    "0x01": "120p (*1)",
    "0x02": "100p",
    "0x03": "60p (*2)",
    "0x04": "50p",
    "0x05": "30p (*3)",
    "0x06": "25p",
    "0x07": "24p (*4)",
    "0x08": "23.98p",
    "0x09": "29.97p",
    "0x0A": "59.94p",
    "0x0B": "19.98p",
    "0x0C": "14.99p",
    "0x0D": "12.50p",
    "0x0E": "12.00p",
    "0x0F": "11.99p",
    "0x10": "10.00p",
    "0x11": "9.99p",
    "0x12": "6.00p",
    "0x13": "5.99p",
    "0x14": "5.00p",
    "0x15": "4.995p",
    "0x16": "24.00p",
    "0x17": "119.88p",
    "0x41": "120i (*1)",
    "0x42": "100i",
    "0x43": "60i (*2)",
    "0x44": "50i",
    "0x45": "30i (*3)",
    "0x46": "25i",
    "0x47": "24i (*4)",
    "0x48": "23.98i",
    "0x49": "29.97i",
    "0x4A": "59.94i",
    "0x4B": "19.98i",
    "0x4C": "14.99i",
    "0x4D": "12.50i",
    "0x4E": "12.00i",
    "0x4F": "11.99i",
    "0x50": "10.00i",
    "0x51": "9.99i",
    "0x52": "6.00i",
    "0x53": "5.99i",
    "0x54": "5.00i",
    "0x55": "4.995i",
    "0x56": "24.00i",
    "0x57": "119.88i"
   }
  },
  "0xD287": {
   "name": "Compression File Format (Still)",
   "values": {
    "0x01": "JPEG",
    "0x02": "HEIF (4:2:2)",
    "0x03": "HEIF (4:2:0)"
   }
  },
  "0xD288": {
   "name": "RAW File Type",
   "values": {
    "0x01": "Compression",
    "0x02": "LossLessL",
    "0x03": "LossLessM",
    "0x04": "LossLessS",
    "0x05": "Uncompression",
    "0x06": "LossLess",
    "0x07": "Compressed(HQ)"
   }
  },
  "0xD289": {
   "name": "Media Slot1 RAW File Type",
   "values": {
    "0x01": "Compression",
    "0x02": "LossLessL",
    "0x03": "LossLessM",
    "0x04": "LossLessS",
    "0x05": "Uncompression",
    "0x06": "LossLess",
    "0x07": "Compressed(HQ)"
   }
  },
  "0xD28A": {
   "name": "Media Slot2 RAW File Type",
   "values": {
    "0x01": "Compression",
    "0x02": "LossLessL",
    "0x03": "LossLessM",
    "0x04": "LossLessS",
    "0x05": "Uncompression",
    "0x06": "LossLess",
    "0x07": "Compressed(HQ)"
   }
  },
  "0xD28B": {
   "name": "Media Slot1 File Format (Still)",
   "values": {
    "0x01": "RAW",
    "0x02": "RAW+JPEG",
    "0x03": "JPEG",
    "0x04": "RAW+HEIF",
    "0x05": "HEIF"
   }
  },
  "0xD28C": {
   "name": "Media Slot2 File Format (Still)",
   "values": {
    "0x01": "RAW",
    "0x02": "RAW+JPEG",
    "0x03": "JPEG",
    "0x04": "RAW+HEIF",
    "0x05": "HEIF"
   }
  },
  "0xD28D": {
   "name": "Media SLOT1 Image Quality",
   "values": {
    "0x01": "Extra Fine",
    "0x02": "Fine",
    "0x03": "Standard",
    "0x04": "Light"
   }
  },
  "0xD28E": {
   "name": "Media SLOT2 Image Quality",
   "values": {
    "0x01": "Extra Fine",
    "0x02": "Fine",
    "0x03": "Standard",
    "0x04": "Light"
   }
  },
  "0xD28F": {
   "name": "Media SLOT1 Image Size",
   "values": {
    "0x01": "L",
    "0x02": "M",
    "0x03": "S",
    "0x04": "24M",
    "0x05": "16M",
    "0x06": "13M",
    "0x07": "11M",
    "0x08": "8.4M",
    "0x09": "6.1M",
    "0x0A": "6M",
    "0x0B": "5.6M",
    "0x0C": "4M",
    "0x0D": "2.6M",
    "0x0E": "21M",
    "0x0F": "20M",
    "0x10": "10M",
    "0x11": "9.2M",
    "0x12": "8.7M",
    "0x13": "6.9M",
    "0x14": "4.6M",
    "0x15": "4.1M",
    "0x16": "4M",
    "0x17": "3.9M",
    "0x18": "3.1M",
    "0x19": "VGA"
   }
  },
  "0xD290": {
   "name": "Media SLOT2 Image Size",
   "values": {
    "0x01": "L",
    "0x02": "M",
    "0x03": "S",
    "0x04": "24M",
    "0x05": "16M",
    "0x06": "13M",
    "0x07": "11M",
    "0x08": "8.4M",
    "0x09": "6.1M",
    "0x0A": "6M",
    "0x0B": "5.6M",
    "0x0C": "4M",
    "0x0D": "2.6M",
    "0x0E": "21M",
    "0x0F": "20M",
    "0x10": "10M",
    "0x11": "9.2M",
    "0x12": "8.7M",
    "0x13": "6.9M",
    "0x14": "4.6M",
    "0x15": "4.1M",
    "0x16": "4M",
    "0x17": "3.9M",
    "0x18": "3.1M",
    "0x19": "VGA"
   }
  },
  "0xD292": {
   "name": "Media SLOT1 Quick Format Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD293": {
   "name": "Media SLOT2 Quick Format Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD294": {
   "name": "Cancel Media Format Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD295": {
   "name": "Contents Transfer Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD297": {
   "name": "Save Zoom and Focus Position"
  },
  "0xD298": {
   "name": "Load Zoom and Focus Position"
  },
  "0xD299": {
   "name": "Remote Control Zoom Speed Type",
   "values": {
    "0x00": "Invalid",
    "0x01": "Variable",
    "0x02": "Fixed"
   }
  },
  "0xD29A": {
   "name": "APS-C or Full Switching Setting",
   "values": {
    "0x01": "Full",
    "0x02": "APS-C"
   }
  },
  "0xD29B": {
   "name": "APS-C or Full Switching Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD29C": {
   "name": "Movie Rec Self timer",
   "values": {
    "0x00": "OFF",
    "0x01": "ON"
   }
  },
  "0xD29D": {
   "name": "Movie Rec Self timer Count time",
   "values": {
    "0x0000": "Do not perform a countdown"
   }
  },
  "0xD29E": {
   "name": "Paint/Look Multi Matrix Area Indication",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xD29F": {
   "name": "Movie Rec Self timer Continuous",
   "values": {
    "0x00": "OFF",
    "0x01": "ON"
   }
  },
  "0xD2A0": {
   "name": "Movie Rec Self timer Status",
   "values": {
    "0x00": "Idle",
    "0x01": "Counting down"
   }
  },
  "0xD2A1": {
   "name": "Focus Bracket Shot Num"
  },
  "0xD2A2": {
   "name": "Focus Bracket Focus Range"
  },
  "0xD2A4": {
   "name": "Bulb Timer Setting",
   "values": {
    "0x00": "OFF",
    "0x01": "ON"
   }
  },
  "0xD2A5": {
   "name": "Bulb Exposure Time Setting"
  },
  "0xD2A9": {
   "name": "Monitor LUT Setting Output Dest Assign"
  },
  "0xD2AA": {
   "name": "Monitor LUT Setting 1",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD2AB": {
   "name": "Monitor LUT Setting 2",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD2AC": {
   "name": "Monitor LUT Setting 3",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD2AD": {
   "name": "AF Track for Speed Change",
   "values": {
    "0x01": "Stable",
    "0x02": "Standard",
    "0x03": "Responsive"
   }
  },
  "0xD2AE": {
   "name": "Monitoring Output Display Setting Dest Assign"
  },
  "0xD2AF": {
   "name": "Monitoring Output Display Setting 1",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD2B0": {
   "name": "Monitoring Output Display Setting 2",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xD2B1": {
   "name": "UserBaseLook Output",
   "values": {
    "0x0001": "BT.709",
    "0x0002": "BT.709(Legal)",
    "0x0101": "HLG",
    "0x0102": "HLG(Legal)"
   }
  },
  "0xD2B2": {
   "name": "Play Set of Multi. Media",
   "values": {
    "0x01": "SLOT1",
    "0x02": "SLOT2"
   }
  },
  "0xD2B3": {
   "name": "Video Stream Bit Rate VBR Mode",
   "values": {
    "0x01": "Standard",
    "0x02": "Bit Rate Limit"
   }
  },
  "0xD2B5": {
   "name": "Recording File Number (Movie)",
   "values": {
    "0x01": "Reset",
    "0x02": "Series"
   }
  },
  "0xD2BA": {
   "name": "Flicker Scan Status",
   "values": {
    "0x00": "Invalid",
    "0x01": "Idle",
    "0x02": "Flicker scanning"
   }
  },
  "0xD2BB": {
   "name": "Flicker Scan Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xD2BD": {
   "name": "Tele/Wide Lever Value capability"
  },
  "0xD2BE": {
   "name": "Target Streaming Destination Select",
   "values": {
    "0x01": "Video Stream 1",
    "0x02": "Video Stream 2",
    "0x03": "Video Stream 3",
    "0x04": "Video Stream 4",
    "0x05": "Video Stream 5",
    "0x06": "Video Stream 6",
    "0x07": "Video Stream 7",
    "0x08": "Video Stream 8",
    "0x09": "Video Stream 9",
    "0x0A": "Video Stream 10",
    "0x0B": "Video Stream 11",
    "0x0C": "Video Stream 12",
    "0x0D": "Video Stream 13",
    "0x0E": "Video Stream 14",
    "0x0F": "Video Stream 15",
    "0x10": "Video Stream 16"
   }
  },
  "0xD2BF": {
   "name": "Stream Display Name"
  },
  "0xD2C0": {
   "name": "Video Stream Max Bit Rate"
  },
  "0xE000": {
   "name": "Movie Shooting Mode",
   "values": {
    "0x0001": "OFF",
    "0x0101": "SDR",
    "0x0201": "HDR",
    "0x0301": "CineEI",
    "0x0302": "CineEI Quick",
    "0x0401": "Custom",
    "0x0501": "FlexibleISO"
   }
  },
  "0xE001": {
   "name": "Movie Shooting Mode Color Gamut",
   "values": {
    "0x01": "S-Gamut3.Cine",
    "0x02": "S-Gamut3"
   }
  },
  "0xE002": {
   "name": "Movie Shooting Mode Target Display",
   "values": {
    "0x01": "BT.709",
    "0x02": "BT.2020"
   }
  },
  "0xE004": {
   "name": "Focus TouchSpot Status",
   "values": {
    "0x01": "Stopped",
    "0x02": "Running"
   }
  },
  "0xE005": {
   "name": "Focus Tracking Status",
   "values": {
    "0x01": "OFF",
    "0x02": "Focusing",
    "0x03": "Tracking"
   }
  },
  "0xE006": {
   "name": "Shutter ECS Setting",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xE007": {
   "name": "Shutter ECS Number"
  },
  "0xE008": {
   "name": "Shutter ECS Frequency"
  },
  "0xE009": {
   "name": "Depth of Field Adjustment Mode",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xE00A": {
   "name": "Depth of Field Adjustment Interlocking Mode State",
   "values": {
    "0x01": "ND interlocking mode",
    "0x02": "Gain interlocking mode"
   }
  },
  "0xE00B": {
   "name": "Recorder Clip Name"
  },
  "0xE00C": {
   "name": "Recorder Control Main Setting",
   "values": {
    "0x00": "Rec Disable",
    "0x01": "Rec Enable"
   }
  },
  "0xE00D": {
   "name": "Recorder Control Proxy Setting",
   "values": {
    "0x00": "Rec Disable",
    "0x01": "Rec Enable"
   }
  },
  "0xE00E": {
   "name": "Recorder Start Main",
   "values": {
    "0x00": "Rec Start Disable",
    "0x01": "Rec Start Enable"
   }
  },
  "0xE00F": {
   "name": "Recorder Start Proxy",
   "values": {
    "0x00": "Rec Start Disable",
    "0x01": "Rec Start Enable"
   }
  },
  "0xE010": {
   "name": "Recorder Main Status",
   "values": {
    "0x00": "Idle",
    "0x01": "Ready",
    "0x02": "PreparingToRecord",
    "0x03": "Standby",
    "0x04": "Recording",
    "0x05": "Stopping"
   }
  },
  "0xE011": {
   "name": "Recorder Proxy Status",
   "values": {
    "0x00": "Idle",
    "0x01": "Ready",
    "0x02": "PreparingToRecord",
    "0x03": "Standby",
    "0x04": "Recording",
    "0x05": "Stopping"
   }
  },
  "0xE012": {
   "name": "Recorder Ext Raw Status",
   "values": {
    "0x00": "Idle",
    "0x01": "Ready",
    "0x02": "PreparingToRecord",
    "0x03": "Standby",
    "0x04": "Recording",
    "0x05": "Stopping"
   }
  },
  "0xE013": {
   "name": "Recorder Save Destination",
   "values": {
    "0x0001": "External",
    "0x0010": "Internal",
    "0x0011": "External & Internal"
   }
  },
  "0xE014": {
   "name": "Button Assignment Assignable.1",
   "values": {
    "0x01": "Function 1",
    "0x02": "Function 2",
    "0x03": "Function 3",
    "0x04": "Function 4",
    "0x05": "Function 5",
    "0x06": "Function 6",
    "0x07": "Function 7",
    "0x08": "Function 8",
    "0x09": "Function 9"
   }
  },
  "0xE015": {
   "name": "Button Assignment Assignable.2",
   "values": {
    "0x01": "Function 1",
    "0x02": "Function 2",
    "0x03": "Function 3",
    "0x04": "Function 4",
    "0x05": "Function 5",
    "0x06": "Function 6",
    "0x07": "Function 7",
    "0x08": "Function 8",
    "0x09": "Function 9"
   }
  },
  "0xE016": {
   "name": "Button Assignment Assignable.3",
   "values": {
    "0x01": "Function 1",
    "0x02": "Function 2",
    "0x03": "Function 3",
    "0x04": "Function 4",
    "0x05": "Function 5",
    "0x06": "Function 6",
    "0x07": "Function 7",
    "0x08": "Function 8",
    "0x09": "Function 9"
   }
  },
  "0xE017": {
   "name": "Button Assignment Assignable.4",
   "values": {
    "0x01": "Function 1",
    "0x02": "Function 2",
    "0x03": "Function 3",
    "0x04": "Function 4",
    "0x05": "Function 5",
    "0x06": "Function 6",
    "0x07": "Function 7",
    "0x08": "Function 8",
    "0x09": "Function 9"
   }
  },
  "0xE018": {
   "name": "Button Assignment Assignable.5",
   "values": {
    "0x01": "Function 1",
    "0x02": "Function 2",
    "0x03": "Function 3",
    "0x04": "Function 4",
    "0x05": "Function 5",
    "0x06": "Function 6",
    "0x07": "Function 7",
    "0x08": "Function 8",
    "0x09": "Function 9"
   }
  },
  "0xE019": {
   "name": "Button Assignment Assignable.6",
   "values": {
    "0x01": "Function 1",
    "0x02": "Function 2",
    "0x03": "Function 3",
    "0x04": "Function 4",
    "0x05": "Function 5",
    "0x06": "Function 6",
    "0x07": "Function 7",
    "0x08": "Function 8",
    "0x09": "Function 9"
   }
  },
  "0xE01A": {
   "name": "Button Assignment Assignable.7",
   "values": {
    "0x01": "Function 1",
    "0x02": "Function 2",
    "0x03": "Function 3",
    "0x04": "Function 4",
    "0x05": "Function 5",
    "0x06": "Function 6",
    "0x07": "Function 7",
    "0x08": "Function 8",
    "0x09": "Function 9"
   }
  },
  "0xE01B": {
   "name": "Button Assignment Assignable.8",
   "values": {
    "0x01": "Function 1",
    "0x02": "Function 2",
    "0x03": "Function 3",
    "0x04": "Function 4",
    "0x05": "Function 5",
    "0x06": "Function 6",
    "0x07": "Function 7",
    "0x08": "Function 8",
    "0x09": "Function 9"
   }
  },
  "0xE01C": {
   "name": "Button Assignment Assignable.9",
   "values": {
    "0x01": "Function 1",
    "0x02": "Function 2",
    "0x03": "Function 3",
    "0x04": "Function 4",
    "0x05": "Function 5",
    "0x06": "Function 6",
    "0x07": "Function 7",
    "0x08": "Function 8",
    "0x09": "Function 9"
   }
  },
  "0xE01D": {
   "name": "Button Assignment Assignable.10",
   "values": {
    "0x01": "Function 1",
    "0x02": "Function 2",
    "0x03": "Function 3",
    "0x04": "Function 4",
    "0x05": "Function 5",
    "0x06": "Function 6",
    "0x07": "Function 7",
    "0x08": "Function 8",
    "0x09": "Function 9"
   }
  },
  "0xE01E": {
   "name": "Button Assignment LensAssignable.1",
   "values": {
    "0x01": "Function 1",
    "0x02": "Function 2",
    "0x03": "Function 3",
    "0x04": "Function 4",
    "0x05": "Function 5",
    "0x06": "Function 6",
    "0x07": "Function 7",
    "0x08": "Function 8",
    "0x09": "Function 9"
   }
  },
  "0xE01F": {
   "name": "SceneFile Index",
   "values": {
    "0x00000000": "None",
    "0x00000001": "Scene 1",
    "0x00000002": "Scene 2",
    "0x00000003": "Scene 3",
    "0x00000004": "Scene 4",
    "0x00000005": "Scene 5",
    "0x00000006": "Scene 6",
    "0x00000007": "Scene 7",
    "0x00000008": "Scene 8",
    "0x00000009": "Scene 9"
   }
  },
  "0xE020": {
   "name": "Current SceneFile Edited",
   "values": {
    "0x01": "Unedited",
    "0x02": "Edited"
   }
  },
  "0xE021": {
   "name": "Movie Play Button",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE022": {
   "name": "Movie Play Pause Button",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE023": {
   "name": "Movie Play Stop Button",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE024": {
   "name": "Movie Forward Button",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE025": {
   "name": "Movie Rewind Button",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE026": {
   "name": "Movie Next Button",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE027": {
   "name": "Movie Prev Button",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE028": {
   "name": "Movie RecReview Button",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE029": {
   "name": "Assignable Button 1",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE02A": {
   "name": "Assignable Button 2",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE02B": {
   "name": "Assignable Button 3",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE02C": {
   "name": "Assignable Button 4",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE02D": {
   "name": "Assignable Button 5",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE02E": {
   "name": "Assignable Button 6",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE02F": {
   "name": "Assignable Button 7",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE030": {
   "name": "Assignable Button 8",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE031": {
   "name": "Assignable Button 9",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE032": {
   "name": "Assignable Button 10",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE033": {
   "name": "LensAssignable Button 1",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE035": {
   "name": "Assignable Button Indicator 1",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE036": {
   "name": "Assignable Button Indicator 2",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE037": {
   "name": "Assignable Button Indicator 3",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE038": {
   "name": "Assignable Button Indicator 4",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE039": {
   "name": "Assignable Button Indicator 5",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE03A": {
   "name": "Assignable Button Indicator 6",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE03B": {
   "name": "Assignable Button Indicator 7",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE03C": {
   "name": "Assignable Button Indicator 8",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE03D": {
   "name": "Assignable Button Indicator 9",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE03E": {
   "name": "Assignable Button Indicator 10",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE03F": {
   "name": "LensAssignable Button Indicator 1",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE040": {
   "name": "Zoom Position Setting"
  },
  "0xE041": {
   "name": "Zoom Position Current Value"
  },
  "0xE042": {
   "name": "Focus Position Setting"
  },
  "0xE043": {
   "name": "Focus Position Current Value"
  },
  "0xE044": {
   "name": "Focus Mode status",
   "values": {
    "0x01": "Focus Hold",
    "0x02": "MF Operation",
    "0x03": "AF Operation (AF-S/AF-C)"
   }
  },
  "0xE045": {
   "name": "Focus Operation with INT16 Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xE048": {
   "name": "Audio Input CH1 Level Control",
   "values": {
    "0x01": "Auto",
    "0x02": "Manual"
   }
  },
  "0xE049": {
   "name": "Audio Input CH2 Level Control",
   "values": {
    "0x01": "Auto",
    "0x02": "Manual"
   }
  },
  "0xE04A": {
   "name": "Audio Input CH3 Level Control",
   "values": {
    "0x01": "Auto",
    "0x02": "Manual"
   }
  },
  "0xE04B": {
   "name": "Audio Input CH4 Level Control",
   "values": {
    "0x01": "Auto",
    "0x02": "Manual"
   }
  },
  "0xE04C": {
   "name": "Audio Input CH1 Level"
  },
  "0xE04D": {
   "name": "Audio Input CH2 Level"
  },
  "0xE04E": {
   "name": "Audio Input CH3 Level"
  },
  "0xE04F": {
   "name": "Audio Input CH4 Level"
  },
  "0xE050": {
   "name": "Audio Input Master Level"
  },
  "0xE051": {
   "name": "Audio Input CH1 Input Select",
   "values": {
    "0x01": "Off",
    "0x02": "INPUT1",
    "0x03": "INPUT2",
    "0x04": "Internal MIC",
    "0x05": "Shoe CH1",
    "0x06": "Shoe CH2",
    "0x07": "Stereo Mic Jack (L)",
    "0x08": "Stereo Mic Jack (R)"
   }
  },
  "0xE052": {
   "name": "Audio Input CH2 Input Select",
   "values": {
    "0x01": "Off",
    "0x02": "INPUT1",
    "0x03": "INPUT2",
    "0x04": "Internal MIC",
    "0x05": "Shoe CH1",
    "0x06": "Shoe CH2",
    "0x07": "Stereo Mic Jack (L)",
    "0x08": "Stereo Mic Jack (R)"
   }
  },
  "0xE053": {
   "name": "Audio Input CH3 Input Select",
   "values": {
    "0x01": "Off",
    "0x02": "INPUT1",
    "0x03": "INPUT2",
    "0x04": "Internal MIC",
    "0x05": "Shoe CH1",
    "0x06": "Shoe CH2",
    "0x07": "Stereo Mic Jack (L)",
    "0x08": "Stereo Mic Jack (R)"
   }
  },
  "0xE054": {
   "name": "Audio Input CH4 Input Select",
   "values": {
    "0x01": "Off",
    "0x02": "INPUT1",
    "0x03": "INPUT2",
    "0x04": "Internal MIC",
    "0x05": "Shoe CH1",
    "0x06": "Shoe CH2",
    "0x07": "Stereo Mic Jack (L)",
    "0x08": "Stereo Mic Jack (R)"
   }
  },
  "0xE055": {
   "name": "Audio Input CH1 Wind Filter",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE056": {
   "name": "Audio Input CH2 Wind Filter",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE057": {
   "name": "Audio Input CH3 Wind Filter",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE058": {
   "name": "Audio Input CH4 Wind Filter",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE059": {
   "name": "Audio Output HDMI Monitor CH",
   "values": {
    "0x0003": "CH1/CH2",
    "0x000C": "CH3/CH4"
   }
  },
  "0xE05C": {
   "name": "Pan-Tilt Acceleration Ramp Curve",
   "values": {
    "0x00": "Invalid"
   }
  },
  "0xE05F": {
   "name": "Remote Key Thumbnail Button",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE060": {
   "name": "Remote Key SLOT Select Button",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE061": {
   "name": "Movie Rec Button (Toggle) Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xE067": {
   "name": "Video Rec Format Bitrate Setting",
   "values": {
    "0x00": "None"
   }
  },
  "0xE068": {
   "name": "Valid Rec Video Format",
   "values": {
    "0x00000000": "---",
    "0x00000001": "1920×1080P",
    "0x00000002": "3840×2160P",
    "0x00000003": "4096×2160P",
    "0x00000004": "1280×720P",
    "0x00000101": "1920×1080P 35",
    "0x00000201": "1920×1080P 50",
    "0x00010001": "1920×1080i"
   }
  },
  "0xE06C": {
   "name": "Monitoring Output Format"
  },
  "0xE079": {
   "name": "AF Area Position(x,y) (AF-C)"
  },
  "0xE07A": {
   "name": "Movie Rec Review Playing State",
   "values": {
    "0x00": "Not Playing",
    "0x01": "Playing"
   }
  },
  "0xE080": {
   "name": "Image Stabilization Level (Movie)",
   "values": {
    "0x01": "OFF",
    "0x02": "Low",
    "0x03": "High"
   }
  },
  "0xE082": {
   "name": "Movie Trimming Transfer Support Information"
  },
  "0xE083": {
   "name": "Function of Remote Touch Operation",
   "values": {
    "0x01": "Tracking AF",
    "0x02": "Spot AF",
    "0x03": "AF Area Select"
   }
  },
  "0xE084": {
   "name": "AF Assist",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xE086": {
   "name": "Lens Information Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xE088": {
   "name": "Follow Focus Position Setting"
  },
  "0xE089": {
   "name": "Follow Focus Position Current Value"
  },
  "0xE08D": {
   "name": "Button Assignment Assignable.11",
   "values": {
    "0x01": "Function 1",
    "0x02": "Function 2",
    "0x03": "Function 3",
    "0x04": "Function 4",
    "0x05": "Function 5",
    "0x06": "Function 6",
    "0x07": "Function 7",
    "0x08": "Function 8",
    "0x09": "Function 9"
   }
  },
  "0xE08E": {
   "name": "Assignable Button 11",
   "values": {
    "0x0001": "Up",
    "0x0002": "Down"
   }
  },
  "0xE08F": {
   "name": "Assignable Button Indicator 11",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE092": {
   "name": "Focus Speed Direct Sync"
  },
  "0xE0A0": {
   "name": "Movie Quality FullAuto Mode",
   "values": {
    "0x01": "OFF (Manual)",
    "0x02": "ON (Auto)"
   }
  },
  "0xE0A4": {
   "name": "De-Squeeze Display Ratio"
  },
  "0xE0A5": {
   "name": "IR Remote Setting",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xE0A6": {
   "name": "IP Setup Protocol Setting",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xE0AA": {
   "name": "Audio Input1 Type Select",
   "values": {
    "0x01": "MIC",
    "0x02": "LINE"
   }
  },
  "0xE0AB": {
   "name": "Audio Input2 Type Select",
   "values": {
    "0x01": "MIC",
    "0x02": "LINE"
   }
  },
  "0xE0AF": {
   "name": "Camera Power Status",
   "values": {
    "0x01": "Off",
    "0x02": "Standby",
    "0x03": "Power On",
    "0x04": "Transitioning from PowerOn to Standby",
    "0x05": "Transitioning from Standby to Power On"
   }
  },
  "0xE0B0": {
   "name": "Recordable Power Sources"
  },
  "0xE0B5": {
   "name": "Video Rec Format Quality",
   "values": {
    "0x0001": "Level1",
    "0x0002": "Level2",
    "0x0003": "Level3"
   }
  },
  "0xE0B6": {
   "name": "Pan Position Current Value"
  },
  "0xE0B7": {
   "name": "Pan Position Status",
   "values": {
    "0x0001": "idle",
    "0x0002": "moving",
    "0x0003": "unknown",
    "0x0004": "uninitialized"
   }
  },
  "0xE0B8": {
   "name": "Tilt Position Current Value"
  },
  "0xE0B9": {
   "name": "Tilt Position Status",
   "values": {
    "0x0001": "idle",
    "0x0002": "moving",
    "0x0003": "unknown",
    "0x0004": "uninitialized"
   }
  },
  "0xE0BA": {
   "name": "Pan Limit Range Minimum"
  },
  "0xE0BB": {
   "name": "Pan Limit Range Maximum"
  },
  "0xE0BC": {
   "name": "Tilt Limit Range Minimum"
  },
  "0xE0BD": {
   "name": "Tilt Limit Range Maximum"
  },
  "0xE0BE": {
   "name": "Pan Limit Mode",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xE0BF": {
   "name": "Tilt Limit Mode",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xE0C0": {
   "name": "ControlPTZF Binary Version"
  },
  "0xE0CB": {
   "name": "Preset PTZF Number of Slots"
  },
  "0xE0CC": {
   "name": "SetPresetPTZF Binary Version"
  },
  "0xE0CD": {
   "name": "Enlarge Screen Setting",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xE0CE": {
   "name": "Live View Image Quality by Numerical Value"
  },
  "0xE0D2": {
   "name": "Red Tally Lamp Control",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xE0D3": {
   "name": "Green Tally Lamp Control",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xE0D4": {
   "name": "Yellow Tally Lamp Control",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xE0D5": {
   "name": "Debug Mode",
   "values": {
    "0x01": "OFF",
    "0x02": "Pseudo-update with Fake Firmware"
   }
  },
  "0xE0D9": {
   "name": "ContentInfoList update time (SLOT1)"
  },
  "0xE0DA": {
   "name": "ContentInfoList update time (SLOT2)"
  },
  "0xE0E1": {
   "name": "Call Setting",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xE0E2": {
   "name": "Manual Input for ND Filter Value",
   "values": {
    "0xFFFFFFFFFFFFFFFF": "nothing to display."
   }
  },
  "0xE0E3": {
   "name": "Log Shooting Mode (Still)",
   "values": {
    "0x0001": "OFF",
    "0x0501": "FlexibleISO"
   }
  },
  "0xE0E4": {
   "name": "Log Shooting Mode Color Gamut (Still)",
   "values": {
    "0x01": "S-Gamut3.Cine",
    "0x02": "S-Gamut3"
   }
  },
  "0xE0E5": {
   "name": "ND Filter Position Setting"
  },
  "0xE0E6": {
   "name": "SceneFile Command Version"
  },
  "0xE0F0": {
   "name": "Eframing Tracking Start Mode",
   "values": {
    "0x01": "Auto",
    "0x02": "Manual"
   }
  },
  "0xE0F1": {
   "name": "Eframing Production Effect",
   "values": {
    "0x0001": "Off",
    "0x0002": "Interval Zoom In/Out (15sec)",
    "0x0003": "Interval Zoom In/Out (30sec)"
   }
  },
  "0xE0F3": {
   "name": "DeleteContent Operation Enable Status (SLOT1)",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xE0F4": {
   "name": "DeleteContent Operation Enable Status (SLOT2)",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xE0F5": {
   "name": "Different Set for S&Q/Mv",
   "values": {
    "0x01": "ON (Different)",
    "0x02": "OFF (Identical)"
   }
  },
  "0xE0F8": {
   "name": "Eframing Auto Framing",
   "values": {
    "0x01": "OFF",
    "0x02": "ON"
   }
  },
  "0xE0F9": {
   "name": "SceneFile Indexes Available for Download",
   "values": {
    "0x00000000": "None"
   }
  },
  "0xE0FA": {
   "name": "Shooting Enable Setting (License)",
   "values": {
    "0x01": "Enable",
    "0x02": "Disable"
   }
  },
  "0xE0FE": {
   "name": "Maximum Size of Image ID String"
  },
  "0xE100": {
   "name": "Digital Extender Magnification Setting"
  },
  "0xE101": {
   "name": "SceneFile Upload Operation Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xE102": {
   "name": "SceneFile Download Operation Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  },
  "0xE10E": {
   "name": "Grid Line Display (Playback)",
   "values": {
    "0x01": "Off",
    "0x02": "On"
   }
  },
  "0xE10F": {
   "name": "Grid Line Type",
   "values": {
    "0x01": "Rule of 3rds Grid",
    "0x02": "Square Grid",
    "0x03": "Diag. + Square Grid",
    "0x04": "Golden Ratio Grid",
    "0x11": "Custom 1",
    "0x12": "Custom 2",
    "0x13": "Custom 3",
    "0x14": "Custom 4"
   }
  },
  "0xE110": {
   "name": "CustomGridLineFile Command Version"
  },
  "0xE112": {
   "name": "Stream Button Enable Status",
   "values": {
    "0x00": "Disable",
    "0x01": "Enable"
   }
  }
 }
}

"""#
}

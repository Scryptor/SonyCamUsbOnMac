package local.zve10viewer

import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.settings.Preset
import local.zve10viewer.settings.PresetCatalog
import local.zve10viewer.settings.SettingID
import local.zve10viewer.settings.SettingOption
import local.zve10viewer.sony.PropertyRange
import local.zve10viewer.sony.SonyProperty
import local.zve10viewer.sony.SonyPropertyParser
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Свойство камеры для проверок: значение, можно ли менять, варианты или диапазон. */
private fun property(id: SettingID, value: Long, writable: Boolean = true, values: List<Long> = emptyList(),
                     range: PropertyRange? = null) =
    SonyProperty(id.code, PtpType.UINT32, if (writable) 1 else 0, 1, PtpValue.Num(value), range, values, values)

private fun cameraWith(vararg properties: SonyProperty): Map<Int, SonyProperty> = properties.associateBy { it.code }

class PresetsTest {
    /** Запись прежних версий: без «skip» — как пресет пользователя на планшете. */
    private val oldJson = """[{"name":"Пресет 1","values":{"whiteBalance":2,"wbShiftAB":192,"wbShiftGM":192,"iso":400,""" +
        """"shutter":65566,"aperture":320,"metering":32769,"dro":31,"focusMode":32772,"focusArea":1,"creativeStyle":1,""" +
        """"pictureProfile":0}}]"""

    @Test fun oldPresetsWithoutSkipLoadAsTheyWere() {
        val presets = PresetCatalog.fromJson(oldJson)
        assertEquals(1, presets.size)
        val preset = presets[0]
        assertEquals("Пресет 1", preset.name)
        assertEquals(12, preset.values.size)
        assertEquals(400L, preset.values[SettingID.iso])
        assertEquals(emptySet<SettingID>(), preset.skip)
        // меняет всё, что помнит, — как раньше
        assertEquals(preset.values, PresetCatalog.effective(preset))
        assertEquals("1/30 · f/3.2 · ISO 400 · AWB · AF-C", PresetCatalog.summary(preset))
        // записанный обратно — тот же пресет, и без «skip»
        val written = PresetCatalog.toJson(presets)
        assertFalse(JSONArray(written).getJSONObject(0).has("skip"))
        assertEquals(presets, PresetCatalog.fromJson(written))
    }

    @Test fun skipIsWrittenSortedAndOnlyWhenThereIsOne() {
        val preset = Preset("Вечер", mapOf(SettingID.iso to 800L, SettingID.colorTemperature to 3200L, SettingID.shutter to 0x10032L),
            skip = setOf(SettingID.iso, SettingID.colorTemperature))
        val json = PresetCatalog.toJson(listOf(preset, Preset("День", mapOf(SettingID.iso to 100L))))
        val items = JSONArray(json)
        assertEquals("""["colorTemperature","iso"]""", items.getJSONObject(0).getJSONArray("skip").toString())
        assertFalse(items.getJSONObject(1).has("skip"))
        assertEquals(listOf(preset, Preset("День", mapOf(SettingID.iso to 100L))), PresetCatalog.fromJson(json))
    }

    @Test fun unknownSettingsFromANewerVersionAreDropped() {
        val json = """[{"name":"Новый","values":{"iso":800,"frameRate":25},"skip":["iso","frameRate"]}]"""
        assertEquals(listOf(Preset("Новый", mapOf(SettingID.iso to 800L), setOf(SettingID.iso))), PresetCatalog.fromJson(json))
    }

    @Test fun effectiveLeavesOutSwitchedOffSettings() {
        val preset = Preset("Вечер", mapOf(SettingID.iso to 800L, SettingID.shutter to 0x10032L, SettingID.focusMode to 2L),
            skip = setOf(SettingID.iso))
        assertEquals(mapOf(SettingID.shutter to 0x10032L, SettingID.focusMode to 2L), PresetCatalog.effective(preset))
        // в порядке каталога
        assertEquals(listOf(SettingID.shutter, SettingID.focusMode), PresetCatalog.effective(preset).keys.toList())
    }

    @Test fun temperatureAppliesOnlyWithManualWhiteBalanceOrWithoutOne() {
        fun effective(values: Map<SettingID, Long>, skip: Set<SettingID> = emptySet()) =
            PresetCatalog.effective(Preset("п", values, skip))
        // другой баланс белого — температуру не пишем
        assertEquals(mapOf(SettingID.whiteBalance to 2L), effective(mapOf(SettingID.whiteBalance to 2L, SettingID.colorTemperature to 3200L)))
        // «Цвет. темп.» — пишем
        assertEquals(mapOf(SettingID.whiteBalance to 0x8012L, SettingID.colorTemperature to 3200L),
            effective(mapOf(SettingID.whiteBalance to 0x8012L, SettingID.colorTemperature to 3200L)))
        // баланс белого пресет не меняет (выключен или его нет) — температура применяется сама
        assertEquals(mapOf(SettingID.colorTemperature to 3200L),
            effective(mapOf(SettingID.whiteBalance to 4L, SettingID.colorTemperature to 3200L), setOf(SettingID.whiteBalance)))
        assertEquals(mapOf(SettingID.colorTemperature to 3200L), effective(mapOf(SettingID.colorTemperature to 3200L)))
        // выключенная температура не пишется и в «Цвет. темп.»
        assertEquals(mapOf(SettingID.whiteBalance to 0x8012L),
            effective(mapOf(SettingID.whiteBalance to 0x8012L, SettingID.colorTemperature to 3200L), setOf(SettingID.colorTemperature)))
    }

    @Test fun presetIsOnTheCameraWhenEverythingItAppliesIsThere() {
        val camera = cameraWith(property(SettingID.iso, 800), property(SettingID.whiteBalance, 2),
            property(SettingID.colorTemperature, 5600), property(SettingID.shutter, 0x10032))
        assertTrue(PresetCatalog.matches(Preset("п", mapOf(SettingID.iso to 800L, SettingID.shutter to 0x10032L)), camera))
        assertFalse(PresetCatalog.matches(Preset("п", mapOf(SettingID.iso to 400L, SettingID.shutter to 0x10032L)), camera))
        // выключенное не считается
        assertTrue(PresetCatalog.matches(Preset("п", mapOf(SettingID.iso to 400L, SettingID.shutter to 0x10032L),
            setOf(SettingID.iso)), camera))
        // температура не применится (баланс белого — AWB) — и не сравнивается
        assertTrue(PresetCatalog.matches(Preset("п", mapOf(SettingID.whiteBalance to 2L, SettingID.colorTemperature to 3200L)), camera))
        // свойства у камеры нет — не стоит
        assertFalse(PresetCatalog.matches(Preset("п", mapOf(SettingID.iso to 800L, SettingID.focusMode to 2L)), camera))
        // ничего не меняет — не стоит
        assertFalse(PresetCatalog.matches(Preset("п", emptyMap()), camera))
        assertFalse(PresetCatalog.matches(Preset("п", mapOf(SettingID.iso to 800L), setOf(SettingID.iso)), camera))
        assertFalse(PresetCatalog.matches(Preset("п", mapOf(SettingID.iso to 800L)), emptyMap()))
    }

    @Test fun recaptureTakesWhatTheCameraLetsChangeAndKeepsTheRest() {
        val preset = Preset("Вечер", mapOf(SettingID.iso to 800L, SettingID.shutter to 0x10032L, SettingID.focusMode to 2L,
            SettingID.dro to 31L), skip = setOf(SettingID.iso))
        val camera = cameraWith(property(SettingID.iso, 1600), property(SettingID.shutter, 0x1001E, writable = false),
            property(SettingID.focusMode, 0x8004), property(SettingID.aperture, 280))
        val result = PresetCatalog.recapture(preset, camera)
        assertEquals("Вечер", result.name)
        assertEquals(setOf(SettingID.iso), result.skip)
        // ISO — с камеры и выключенный; выдержку камера менять не даёт — прежняя; DRO у камеры нет — прежний;
        // диафрагмы в пресете не было — теперь есть
        assertEquals(mapOf(SettingID.iso to 1600L, SettingID.shutter to 0x10032L, SettingID.focusMode to 0x8004L,
            SettingID.dro to 31L, SettingID.aperture to 280L), result.values)
    }

    @Test fun summaryNamesTheMainValues() {
        fun summary(values: Map<SettingID, Long>, skip: Set<SettingID> = emptySet()) = PresetCatalog.summary(Preset("п", values, skip))
        assertEquals("1/50 · f/2.8 · ISO 800 · 3200 K · AF-C", summary(mapOf(SettingID.shutter to 0x10032L, SettingID.aperture to 280L,
            SettingID.iso to 800L, SettingID.whiteBalance to 0x8012L, SettingID.colorTemperature to 3200L,
            SettingID.exposureComp to 0L, SettingID.focusMode to 0x8004L, SettingID.dro to 31L)))
        assertEquals("ISO AUTO · AWB · +0.7", summary(mapOf(SettingID.iso to 0xFFFFFFL, SettingID.exposureComp to 700L,
            SettingID.whiteBalance to 2L)))
        // баланс белого выключен, температура — нет: одни кельвины
        assertEquals("3200 K", summary(mapOf(SettingID.whiteBalance to 4L, SettingID.colorTemperature to 3200L), setOf(SettingID.whiteBalance)))
        // «Цвет. темп.» без температуры
        assertEquals("Цвет. темп.", summary(mapOf(SettingID.whiteBalance to 0x8012L)))
        // ничего из главного — названия того, что меняет, в порядке каталога
        assertEquals("Замер · Стиль", summary(mapOf(SettingID.creativeStyle to 1L, SettingID.metering to 1L)))
        assertEquals("ничего не меняет", summary(mapOf(SettingID.iso to 100L), setOf(SettingID.iso)))
        assertEquals("ничего не меняет", summary(emptyMap()))
    }

    @Test fun optionsComeFromTheCameraAndKeepThePresetValue() {
        val iso = property(SettingID.iso, 200, values = listOf(100, 200, 400, 0xFFFFFF))
        val preset = Preset("п", mapOf(SettingID.iso to 6400L, SettingID.focusMode to 2L, SettingID.colorTemperature to 3150L))
        // подписи — как в меню Fn
        assertEquals(listOf(SettingOption(100, "100"), SettingOption(200, "200"), SettingOption(400, "400"), SettingOption(0xFFFFFF, "AUTO")),
            PresetCatalog.options(SettingID.iso, iso, Preset("п", mapOf(SettingID.iso to 400L))))
        // значения пресета среди вариантов нет — оно первым, со своей подписью
        assertEquals(SettingOption(6400, "6400"), PresetCatalog.options(SettingID.iso, iso, preset).first())
        assertEquals(5, PresetCatalog.options(SettingID.iso, iso, preset).size)
        // камера сейчас не даёт менять — выбирать не из чего
        assertEquals(emptyList<SettingOption>(), PresetCatalog.options(SettingID.focusMode,
            property(SettingID.focusMode, 1, writable = false, values = listOf(1, 2)), preset))
        // камеры нет на связи — тоже, и для температуры
        assertEquals(emptyList<SettingOption>(), PresetCatalog.options(SettingID.iso, null, preset))
        assertEquals(emptyList<SettingOption>(), PresetCatalog.options(SettingID.colorTemperature, null, preset))
        // температура, которую камера сейчас не даёт менять, — из запасного диапазона 2500–9900 K через 100
        val fallback = PresetCatalog.options(SettingID.colorTemperature,
            property(SettingID.colorTemperature, 5600, writable = false, range = PropertyRange(2500, 9900, 100)), Preset("п", emptyMap()))
        assertEquals(75, fallback.size)
        assertEquals(SettingOption(2500, "2500 K"), fallback.first())
        assertEquals(SettingOption(9900, "9900 K"), fallback.last())
        // …а значение пресета не на шаге — первым
        assertEquals(SettingOption(3150, "3150 K"), PresetCatalog.options(SettingID.colorTemperature,
            property(SettingID.colorTemperature, 5600, writable = false), preset).first())
        // диапазон камеры, когда она даёт менять
        assertEquals(listOf(2500L, 3000L, 3500L), PresetCatalog.options(SettingID.colorTemperature,
            property(SettingID.colorTemperature, 3000, range = PropertyRange(2500, 3500, 500)), Preset("п", emptyMap())).map { it.value })
    }

    @Test fun switchingASettingOnTakesTheCameraValueAndOffRemembersIt() {
        val camera = cameraWith(property(SettingID.iso, 1600), property(SettingID.colorTemperature, 4300))
        val preset = Preset("п", mapOf(SettingID.shutter to 0x10032L))
        // не было — берём с камеры
        val withIso = PresetCatalog.including(preset, SettingID.iso, true, camera)!!
        assertEquals(1600L, withIso.values[SettingID.iso])
        assertTrue(PresetCatalog.includes(withIso, SettingID.iso))
        // выключили — значение помнит
        val off = PresetCatalog.including(withIso, SettingID.iso, false, camera)!!
        assertFalse(PresetCatalog.includes(off, SettingID.iso))
        assertEquals(1600L, off.values[SettingID.iso])
        // включили обратно — своё, а не то, что сейчас на камере
        val on = PresetCatalog.including(off, SettingID.iso, true, cameraWith(property(SettingID.iso, 100)))!!
        assertEquals(1600L, on.values[SettingID.iso])
        assertEquals(emptySet<SettingID>(), on.skip)
        // температура — сохранённая камерой ручная, без камеры — 5600 K
        assertEquals(4300L, PresetCatalog.including(preset, SettingID.colorTemperature, true, camera)!!.values[SettingID.colorTemperature])
        assertEquals(5600L, PresetCatalog.including(preset, SettingID.colorTemperature, true, emptyMap())!!.values[SettingID.colorTemperature])
        // взять неоткуда — включить нельзя
        assertNull(PresetCatalog.including(preset, SettingID.focusMode, true, camera))
        // выключить то, чего нет, — ничего не меняется
        assertEquals(preset, PresetCatalog.including(preset, SettingID.focusMode, false, camera))
        // не из каталога — нельзя
        assertNull(PresetCatalog.including(preset, SettingID.battery, true, camera))
    }

    @Test fun movingNamingAndCopying() {
        val a = Preset("A", emptyMap())
        val b = Preset("B", emptyMap())
        val c = Preset("C", emptyMap())
        assertEquals(listOf(a, c, b), PresetCatalog.moved(listOf(a, b, c), 2, 1))
        assertEquals(listOf(b, c, a), PresetCatalog.moved(listOf(a, b, c), 0, 2))
        assertNull(PresetCatalog.moved(listOf(a, b, c), 0, 3))
        assertNull(PresetCatalog.moved(listOf(a, b, c), -1, 0))
        assertEquals("Пресет 1", PresetCatalog.newName(emptyList()))
        assertEquals("Пресет 3", PresetCatalog.newName(listOf(a, b)))
        assertEquals("Вечер — копия", PresetCatalog.copyName("Вечер"))
    }

    @Test fun windowGroupsHoldExactlyTheCatalog() {
        val grouped = PresetCatalog.groups.flatMap { it.second }
        assertEquals(PresetCatalog.order.size, grouped.size)
        assertEquals(PresetCatalog.order.toSet(), grouped.toSet())
        assertEquals(listOf("Экспозиция", "Цвет и картинка", "Фокус"), PresetCatalog.groups.map { it.first })
        assertEquals(listOf(SettingID.shutter, SettingID.aperture, SettingID.iso, SettingID.exposureComp, SettingID.metering,
            SettingID.dro), PresetCatalog.groups[0].second)
    }

    @Test fun freshPresetFromTheRealCameraIsOnTheCamera() {
        val camera = SonyPropertyParser.parse(PresetsTest::class.java.getResource("/fixtures/props_settled.bin")!!.readBytes())
        val preset = PresetCatalog.capture("Пресет 1", camera)
        assertTrue(PresetCatalog.matches(preset, camera))
        // «Взять с камеры» с той же камеры ничего не меняет
        assertEquals(preset, PresetCatalog.recapture(preset, camera))
        // одно значение не то — уже не стоит
        val iso = SettingID.iso.code
        val other = camera + (iso to camera.getValue(iso).copy(current = PtpValue.Num(6400)))
        assertFalse(PresetCatalog.matches(preset, other))
    }
}

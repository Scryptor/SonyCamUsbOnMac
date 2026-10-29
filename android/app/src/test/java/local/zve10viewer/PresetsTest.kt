package local.zve10viewer

import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.settings.CaptureMode
import local.zve10viewer.settings.Preset
import local.zve10viewer.settings.PresetCatalog
import local.zve10viewer.settings.PresetChange
import local.zve10viewer.settings.PresetLibrary
import local.zve10viewer.settings.SettingID
import local.zve10viewer.settings.SettingOption
import local.zve10viewer.settings.WhiteBalanceTemperature
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
        // без режима — и в фото, и в видео: две независимые копии
        assertEquals(listOf(CaptureMode.photo, CaptureMode.video), presets.map { it.mode })
        assertEquals(presets[0].copy(mode = CaptureMode.video), presets[1])
        val preset = presets[0]
        assertEquals("Пресет 1", preset.name)
        assertEquals(12, preset.values.size)
        assertEquals(400L, preset.values[SettingID.iso])
        assertEquals(emptySet<SettingID>(), preset.skip)
        // меняет всё, что помнит, — как раньше
        assertEquals(preset.values, PresetCatalog.effective(preset))
        assertEquals("1/30 · f/3.2 · ISO 400 · AWB · AF-C", PresetCatalog.summary(preset))
        // записанный обратно — те же пресеты, у каждого режим, и без «skip»
        val written = PresetCatalog.toJson(presets)
        assertFalse(JSONArray(written).getJSONObject(0).has("skip"))
        assertEquals(listOf("photo", "video"), List(2) { JSONArray(written).getJSONObject(it).getString("mode") })
        assertEquals(presets, PresetCatalog.fromJson(written))
    }

    @Test fun modeIsWrittenAndReadBack() {
        val presets = listOf(Preset("Улица", mapOf(SettingID.iso to 100L)),
            Preset("Интервью", mapOf(SettingID.shutter to 0x10032L), mode = CaptureMode.video),
            Preset("Портрет", mapOf(SettingID.aperture to 180L), skip = setOf(SettingID.aperture)))
        assertEquals(presets, PresetCatalog.fromJson(PresetCatalog.toJson(presets)))
    }

    @Test fun presetsFromBeforeModesGoIntoBothListsAndKeepTheirOrder() {
        val json = """[{"name":"А","values":{"iso":100}},{"name":"В","mode":"video","values":{"iso":200}},""" +
            """{"name":"Б","values":{"iso":400}}]"""
        val a = Preset("А", mapOf(SettingID.iso to 100L))
        val b = Preset("Б", mapOf(SettingID.iso to 400L))
        val v = Preset("В", mapOf(SettingID.iso to 200L), mode = CaptureMode.video)
        // фото-копии — на местах прежних пресетов, видео-копии — в конце, по порядку
        assertEquals(listOf(a, v, b, a.copy(mode = CaptureMode.video), b.copy(mode = CaptureMode.video)), PresetCatalog.fromJson(json))
        val all = PresetCatalog.fromJson(json)
        assertEquals(listOf("А", "Б"), PresetLibrary.indices(CaptureMode.photo, all).map { all[it].name })
        assertEquals(listOf("В", "А", "Б"), PresetLibrary.indices(CaptureMode.video, all).map { all[it].name })
    }

    @Test fun presetOfAnUnknownModeIsSkipped() {
        val json = """[{"name":"S&Q","mode":"slowQuick","values":{"iso":800}},{"name":"Фото","mode":"photo","values":{"iso":100}}]"""
        assertEquals(listOf(Preset("Фото", mapOf(SettingID.iso to 100L))), PresetCatalog.fromJson(json))
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
        val json = """[{"name":"Новый","mode":"photo","values":{"iso":800,"frameRate":25},"skip":["iso","frameRate"]}]"""
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
        // после удаления «Пресет 2» уже есть — имя не повторяется
        assertEquals("Пресет 3", PresetCatalog.newName(listOf(Preset("Пресет 2", emptyMap()))))
        assertEquals("Пресет 4", PresetCatalog.newName(listOf(Preset("Пресет 3", emptyMap()), Preset("Пресет 2", emptyMap()))))
        assertEquals("Вечер — копия", PresetCatalog.copyName("Вечер"))
    }

    @Test fun windowGroupsHoldExactlyTheCatalog() {
        // Creative Look в группах нет: «Все настройки» показывают строку группы и без свойства камеры, поэтому на место
        // «Творческого стиля» его ставит SettingCatalog.resolved (OtherCamerasTest); остальное в каталоге — ровно по группам
        val catalog = PresetCatalog.order.filter { it != SettingID.creativeLook }
        val grouped = PresetCatalog.groups.flatMap { it.second }
        assertEquals(catalog.size, grouped.size)
        assertEquals(catalog.toSet(), grouped.toSet())
        assertEquals(listOf("Экспозиция", "Цвет и картинка", "Фокус"), PresetCatalog.groups.map { it.first })
        assertEquals(listOf(SettingID.shutter, SettingID.aperture, SettingID.iso, SettingID.exposureComp, SettingID.metering,
            SettingID.dro), PresetCatalog.groups[0].second)
    }

    @Test fun lookRestoresTheCameraValuesOfWhatThePresetChanges() {
        val camera = SonyPropertyParser.parse(PresetsTest::class.java.getResource("/fixtures/props_settled.bin")!!.readBytes())
        val preset = Preset("P", mapOf(SettingID.iso to 6400L, SettingID.aperture to 560L, SettingID.dro to 1L), skip = setOf(SettingID.dro))
        assertEquals(mapOf(SettingID.iso to 200L, SettingID.aperture to 280L), PresetCatalog.restoreValues(preset, camera))
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
        // и это изменение — одно
        assertEquals(listOf("ISO ${preset.values[SettingID.iso]} → 6400"), PresetCatalog.changes(preset, other).map { it.label })
        assertEquals(emptyList<PresetChange>(), PresetCatalog.changes(preset, camera))
    }

    @Test fun newPresetGoesIntoTheListOfItsModeAndRecaptureKeepsIt() {
        val camera = cameraWith(property(SettingID.iso, 800))
        assertEquals(CaptureMode.video, PresetCatalog.capture("Интервью", camera, CaptureMode.video).mode)
        assertEquals(CaptureMode.photo, PresetCatalog.capture("Улица", camera).mode)
        val video = Preset("Интервью", mapOf(SettingID.iso to 100L), mode = CaptureMode.video)
        assertEquals(CaptureMode.video, PresetCatalog.recapture(video, camera).mode)
    }

    @Test fun movingWithinAModeLeavesTheOtherModeWhereItWas() {
        val p1 = Preset("Ф1", emptyMap())
        val p2 = Preset("Ф2", emptyMap())
        val p3 = Preset("Ф3", emptyMap())
        val v1 = Preset("В1", emptyMap(), mode = CaptureMode.video)
        val v2 = Preset("В2", emptyMap(), mode = CaptureMode.video)
        val all = listOf(p1, v1, p2, v2, p3)
        assertEquals(listOf(0, 2, 4), PresetLibrary.indices(CaptureMode.photo, all))
        assertEquals(listOf(1, 3), PresetLibrary.indices(CaptureMode.video, all))
        // первый фото — в конец своего списка; видео на своих местах
        assertEquals(listOf(p2, v1, p3, v2, p1), PresetLibrary.moved(all, CaptureMode.photo, 0, 2))
        assertEquals(listOf(p1, v2, p2, v1, p3), PresetLibrary.moved(all, CaptureMode.video, 1, 0))
        // такого места в списке режима нет
        assertNull(PresetLibrary.moved(all, CaptureMode.video, 0, 2))
        assertNull(PresetLibrary.moved(all, CaptureMode.photo, -1, 0))
    }

    @Test fun currentPresetFollowsMovesDeletesAndCopies() {
        // перенесли его самого — он там, куда перенесли
        assertEquals(3, PresetLibrary.afterMove(1, from = 1, to = 3))
        // перенесли стоявший перед ним за него — он поднялся на место выше, и наоборот
        assertEquals(1, PresetLibrary.afterMove(2, from = 0, to = 2))
        assertEquals(3, PresetLibrary.afterMove(2, from = 4, to = 1))
        // перестановка в стороне его не трогает
        assertEquals(0, PresetLibrary.afterMove(0, from = 2, to = 3))
        // удалили его — опоры нет; удалили выше — он на место выше; ниже — на месте
        assertNull(PresetLibrary.afterDelete(2, removed = 2))
        assertEquals(1, PresetLibrary.afterDelete(2, removed = 0))
        assertEquals(2, PresetLibrary.afterDelete(2, removed = 3))
        // копия встаёт следом за своим: всё ниже сдвигается
        assertEquals(1, PresetLibrary.afterInsert(1, at = 2))
        assertEquals(3, PresetLibrary.afterInsert(2, at = 2))
    }

    @Test fun changesAreWhatTheCameraLetsChangeAndIsNotAsInThePreset() {
        val preset = Preset("Вечер", mapOf(SettingID.shutter to 0x10032L, SettingID.aperture to 280L, SettingID.iso to 800L,
            SettingID.focusMode to 2L, SettingID.exposureComp to 0L, SettingID.dro to 31L, SettingID.metering to 1L),
            skip = setOf(SettingID.metering))
        val camera = cameraWith(
            // выдержку в режиме A камера выбирает сама — это не изменение
            property(SettingID.shutter, 0x10064, writable = false),
            property(SettingID.aperture, 400), property(SettingID.iso, 1600), property(SettingID.focusMode, 0x8004),
            property(SettingID.exposureComp, 700),
            // выключенное в пресете — не изменение
            property(SettingID.metering, 4),
        )
        // DRO у камеры нет — тоже; главное — первым, остальное — по каталогу
        assertEquals(listOf("f/2.8 → f/4", "ISO 800 → 1600", "Экспокорр. ±0.0 → +0.7", "Фокус AF-S → AF-C"),
            PresetCatalog.changes(preset, camera).map { it.label })
        assertEquals(PresetChange(SettingID.iso, 800L, 1600L, "ISO 800 → 1600"), PresetCatalog.changes(preset, camera)[1])
        // всё совпадает — изменений нет
        assertEquals(emptyList<PresetChange>(), PresetCatalog.changes(Preset("п", mapOf(SettingID.iso to 1600L)), camera))
    }

    @Test fun changesOfWhiteBalanceShowTheTemperatureOfEachSide() {
        fun changes(preset: Map<SettingID, Long>, vararg camera: SonyProperty) =
            PresetCatalog.changes(Preset("п", preset), cameraWith(*camera)).map { it.label }
        val manual = WhiteBalanceTemperature.MANUAL_MODE
        // оба в «Цвет. темп.» — другая только температура
        assertEquals(listOf("3200 K → 5600 K"), changes(mapOf(SettingID.whiteBalance to manual, SettingID.colorTemperature to 3200L),
            property(SettingID.whiteBalance, manual), property(SettingID.colorTemperature, 5600)))
        // в пресете AWB, на камере «Цвет. темп.» — температура пресета не применяется, сравнивать нечего
        assertEquals(listOf("AWB → 5600 K"), changes(mapOf(SettingID.whiteBalance to 2L, SettingID.colorTemperature to 3200L),
            property(SettingID.whiteBalance, manual), property(SettingID.colorTemperature, 5600)))
        // в пресете «Цвет. темп.», на камере AWB: температуру камера сейчас менять не даёт
        assertEquals(listOf("3200 K → AWB"), changes(mapOf(SettingID.whiteBalance to manual, SettingID.colorTemperature to 3200L),
            property(SettingID.whiteBalance, 2), property(SettingID.colorTemperature, 5600, writable = false)))
        // другой режим без температуры
        assertEquals(listOf("AWB → Дневной"), changes(mapOf(SettingID.whiteBalance to 2L), property(SettingID.whiteBalance, 4)))
    }

    @Test fun listTitlesAndHintsNameTheMode() {
        assertEquals("Пресеты для фото", PresetLibrary.title(CaptureMode.photo))
        assertEquals("Пресеты для видео и S&Q", PresetLibrary.title(CaptureMode.video))
        assertEquals("Для фото пресетов пока нет. «Новый пресет» запомнит, как сейчас настроена камера.",
            PresetLibrary.emptyHint(CaptureMode.photo))
        assertEquals("Для видео и S&Q пресетов пока нет. «Новый пресет» запомнит, как сейчас настроена камера.",
            PresetLibrary.emptyHint(CaptureMode.video))
        assertEquals("Для видео и S&Q", PresetLibrary.forMode(CaptureMode.video))
    }
}

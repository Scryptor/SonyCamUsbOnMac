package local.zve10viewer.view

/** Что делает касание кадра. */
enum class TapAction {
    /** Открыто меню, панель или окошко: касание закрывает их и больше ничего не делает. */
    closeOverlays,

    /** Элементы были спрятаны: касание только возвращает их. */
    reveal,

    /** Включена пипетка: касание ставит её в эту точку кадра. */
    placeProbe,

    /** Горит «Фокус»: касание ставит туда фокус. */
    focus,

    /** Ничего из этого: касание прячет элементы. */
    hide,
}

/**
 * Куда идёт касание кадра. Порядок тот же, что у Mac (щелчок по кадру): меню — закрыть; пипетка — ставит пипетку, а не фокус, что
 * бы ни горело на кнопке «Фокус» (кнопку это не трогает: выключили пипетку — касание снова ставит фокус); иначе при фокусе
 * касанием — фокус. Одно, чего у Mac нет: спрятанные касанием элементы сначала возвращаются, и такое касание ничего не ставит.
 * Касание мимо кадра (чёрная полоса) пипетку и фокус не ставит.
 */
object TapRoute {
    fun decide(overlayOpen: Boolean, revealing: Boolean, probeOn: Boolean, touchFocusOn: Boolean, onPicture: Boolean): TapAction = when {
        overlayOpen -> TapAction.closeOverlays
        revealing -> TapAction.reveal
        probeOn && onPicture -> TapAction.placeProbe
        touchFocusOn && onPicture -> TapAction.focus
        else -> TapAction.hide
    }
}

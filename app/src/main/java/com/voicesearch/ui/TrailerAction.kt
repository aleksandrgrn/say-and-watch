package com.voicesearch.ui

/** Что делает кнопка «Трейлер» на экране деталей. */
sealed class TrailerAction {
    /** Русский трейлер TMDB — открыть ролик в SmartTube. */
    data class Open(val videoKey: String) : TrailerAction()
    /** Русского трейлера нет — поиск «название трейлер» в SmartTube. */
    object Search : TrailerAction()
    /** Открыть нечем — кнопка серая. */
    object Off : TrailerAction()
}

fun trailerAction(videoKey: String?, title: String, hasSmartTube: Boolean): TrailerAction = when {
    !hasSmartTube -> TrailerAction.Off
    videoKey != null -> TrailerAction.Open(videoKey)
    title.isNotBlank() -> TrailerAction.Search
    else -> TrailerAction.Off
}

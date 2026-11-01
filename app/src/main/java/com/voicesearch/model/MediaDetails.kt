package com.voicesearch.model

/** То, чего нет в выдаче поиска: приходит отдельным запросом при открытии карточки. */
data class MediaDetails(
    val ageRating: String?,     // "16+"
    val runtimeMinutes: Int?,   // фильм целиком или одна серия
    val seasons: Int?,          // только у сериалов
    val trailerKey: String?     // ID ролика на YouTube
)

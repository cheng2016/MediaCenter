package com.mediacenter.app.data.model

enum class MediaType {
    FOLDER,
    IMAGE,
    VIDEO,
    AUDIO,
    WEB,
    TEXT,
    PDF,
    BOOK,
    ARCHIVE,
    APK,
    FILE,
}

enum class MediaFilter {
    ALL,
    RECENT,
    FAVORITE,
    SEARCH,
    IMAGE,
    VIDEO,
    MUSIC,
    WEB,
    TEXT,
    BOOK,
    ARCHIVE,
    APK,
}

enum class SortMode {
    NAME,
    DATE,
    TYPE,
}

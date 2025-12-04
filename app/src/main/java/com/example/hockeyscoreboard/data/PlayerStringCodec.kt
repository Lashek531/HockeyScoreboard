package com.example.hockeyscoreboard.data

fun extractName(raw: String): String =
    raw.substringBefore("||").trim()

fun extractExternalId(raw: String): Long? =
    raw.substringAfter("||", "").toLongOrNull()

fun buildPlayerString(name: String, externalId: Long?): String =
    if (externalId != null) "$name||$externalId" else name

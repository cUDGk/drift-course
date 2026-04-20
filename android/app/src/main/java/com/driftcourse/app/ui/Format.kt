package com.driftcourse.app.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

internal fun formatEpochSeconds(seconds: Long): String =
    TIME_FMT.format(Instant.ofEpochSecond(seconds))

package com.example.hockeyscoreboard

// --- Таймер ---
const val TIMER_TICK_MS: Long = 100L

// Компенсация задержки внешнего табло (подбирается экспериментально)
const val EXTERNAL_START_DELAY_MS: Long = 800L

// Значения по умолчанию для настроек длительности
const val DEFAULT_SHIFT_DURATION_MS: Long = 2L * 60L * 1000L
const val DEFAULT_BREAK_DURATION_MS: Long = 12L * 1000L

// --- Сирена (паттерны оставляем константами в коде) ---
// espController.sendSiren ожидает List<Int>
val SIREN_SHIFT_START_ON_MS: List<Int> = listOf(300, 300)
val SIREN_SHIFT_START_OFF_MS: List<Int> = listOf(500, 0)

val SIREN_SHIFT_END_ON_MS: List<Int> = listOf(1000)
val SIREN_SHIFT_END_OFF_MS: List<Int> = listOf(0)


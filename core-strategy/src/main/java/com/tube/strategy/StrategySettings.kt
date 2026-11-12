package com.tube.strategy

import com.tube.byedpi.Strategies
import com.tube.byedpi.Strategy

/**
 * Настройки, которые нужны подбору и сторожу. Реализуют приложения (Tube — VPN, Tube Player — встроенный прокси),
 * у каждого своё хранилище.
 */
interface StrategySettings {
    /** Выбранная стратегия (встроенная, из набора ByeByeDPI или своя). */
    suspend fun strategy(): Strategy

    /** Строка аргументов своей стратегии; пустая — не задана. */
    suspend fun customArgs(): String

    /** QUIC запрещён (`byedpi -U`). */
    suspend fun blockQuic(): Boolean

    /** Имя сайта в фейковых ClientHello ({sni}). */
    suspend fun fakeSni(): String

    /** Сторож включён. */
    suspend fun watchdog(): Boolean

    /** Стратегия по id: встроенная, из набора ByeByeDPI или своя. */
    suspend fun resolveStrategy(id: String): Strategy? = when (id) {
        Strategies.CUSTOM_ID -> customStrategy(customArgs())
        else -> Strategies.byId(id)
    }

    companion object {
        fun customStrategy(args: String?): Strategy? =
            args?.takeIf { it.isNotBlank() }?.let { Strategy(Strategies.CUSTOM_ID, "CUSTOM", it, "Своя стратегия", "Custom strategy") }
    }
}

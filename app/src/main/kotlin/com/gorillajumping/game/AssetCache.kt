package com.gorillajumping.game

import android.content.res.AssetManager
import android.util.Log

/**
 * Карта и модели грузятся один раз на процесс: игра уходит в меню на телефоне и возвращается,
 * а грузить 90 МБ заново не нужно. Загрузка стартует при первом запуске шлема, пока идёт «Снимите шлем».
 */
object AssetCache {
    @Volatile var cpu: CpuAssets? = null
        private set
    @Volatile var progress = "Загрузка…"
        private set
    @Volatile var error: Throwable? = null
        private set
    private var started = false

    @Synchronized
    fun load(assets: AssetManager) {
        if (started) return
        started = true
        Thread {
            try { cpu = CpuAssets.load(assets) { progress = "Загрузка: $it" } }
            catch (t: Throwable) { error = t; Log.e("GorillaXR", "load", t) }
        }.apply { priority = Thread.NORM_PRIORITY - 1; start() }
    }
}

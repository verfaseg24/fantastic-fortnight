/*
 * Helper for showing proxy status Toast notifications.
 */

package com.duckduckgo.app.browser.proxy

import android.content.Context
import android.widget.Toast

object ProxyNotificationHelper {

    fun showSearching(context: Context) {
        showToast(context, "🔍 Поиск рабочего прокси...")
    }

    fun showConnected(context: Context, entry: ProxyEntry) {
        showToast(context, "✅ Подключено к прокси: ${entry.ip}:${entry.port} (${entry.protocol})")
    }

    fun showFailed(context: Context) {
        showToast(context, "❌ Не удалось найти рабочий прокси")
    }

    fun showChecking(context: Context, current: Int, total: Int) {
        showToast(context, "🔄 Проверка прокси $current/$total...")
    }

    private fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

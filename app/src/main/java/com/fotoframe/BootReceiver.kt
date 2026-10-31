package com.fotoframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Запуск рамки после включения приставки — если это включено в настройках.
 *
 * С Android 10 система не даёт открывать окна из фона, и для запуска после
 * включения нужно право «поверх других приложений». Без него вызов просто
 * ничего не сделает — поэтому в настройках рядом с переключателем написано,
 * как это право выдать. Часть прошивок запуск после включения запрещает
 * совсем; тогда остаётся выбрать рамку экранной заставкой.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && !action.endsWith("QUICKBOOT_POWERON")) return

        val app = context.applicationContext as? App ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!app.settingsStore.settings.first().autostart) return@launch
                val start = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(start)
                Log.i(TAG, "Запуск после включения")
            } catch (e: Exception) {
                Log.w(TAG, "Запуск после включения не удался: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "FotoFrame"
    }
}

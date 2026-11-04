package com.barrix.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Abre Barrix Home al encender el Fire TV. Se lanza dos veces (al arrancar y unos segundos
 * después) porque el Home de Amazon suele aparecer algo más tarde y taparía el nuestro.
 * Solo se activa después de abrir la app al menos una vez tras instalarla.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                open(app)
                Thread.sleep(6000)
                open(app)
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun open(c: Context) {
        try {
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }
}

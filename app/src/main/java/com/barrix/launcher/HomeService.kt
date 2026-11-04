package com.barrix.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Cuando el Home de Amazon intenta aparecer, abre Barrix encima.
 * Se activa una sola vez (desde el botón Activar Home).
 */
class HomeService : AccessibilityService() {

    private var lastLaunch = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != AMAZON_LAUNCHER) return

        // Interruptor por si algún día quieres dejar entrar al Home de Amazon a propósito
        val prefs = getSharedPreferences("barrix", MODE_PRIVATE)
        if (!prefs.getBoolean("redirect_home", true)) return

        // Evita bucles si el sistema dispara varios eventos seguidos
        val now = System.currentTimeMillis()
        if (now - lastLaunch < 400) return
        lastLaunch = now

        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        startActivity(intent)
    }

    override fun onInterrupt() {}

    companion object {
        const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
    }
}

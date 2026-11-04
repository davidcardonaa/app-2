package com.barrix.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Activa el servicio HomeService conectándose por ADB al propio Fire TV (localhost:5555).
 * Requisito: "Depuración ADB" activada en Opciones de desarrollador.
 * La primera vez la tele muestra un aviso de ADB: hay que aceptarlo con el mando.
 */
object HomeActivator {

    private const val PKG = "com.barrix.launcher"
    private const val SERVICE = "$PKG/$PKG.HomeService"

    sealed class Result {
        object Ok : Result()
        data class Error(val message: String) : Result()
    }

    fun activate(context: Context, onResult: (Result) -> Unit) {
        val app = context.applicationContext
        Thread {
            val result = try {
                run(app)
            } catch (e: Exception) {
                Result.Error(e.message ?: e.javaClass.simpleName)
            }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    private fun run(context: Context): Result {
        val keyPair = loadOrCreateKeys(context)
        Dadb.create("localhost", 5555, keyPair).use { adb ->
            // 1. Permiso para escribir ajustes seguros
            val grant = adb.shell("pm grant $PKG android.permission.WRITE_SECURE_SETTINGS")
            if (grant.exitCode != 0) return Result.Error("No se pudo dar permiso: ${grant.allOutput.trim()}")

            // 2. Añadir el servicio sin borrar los que ya existan
            val current = adb.shell("settings get secure enabled_accessibility_services")
                .output.trim()
            val list = current.split(":").filter { it.isNotBlank() && it != "null" }
            if (SERVICE !in list) {
                val newValue = (list + SERVICE).joinToString(":")
                adb.shell("settings put secure enabled_accessibility_services '$newValue'")
            }
            adb.shell("settings put secure accessibility_enabled 1")

            // 3. Verificar
            val check = adb.shell("settings get secure enabled_accessibility_services").output
            return if (SERVICE in check) Result.Ok
            else Result.Error("El sistema no guardó el servicio.")
        }
    }

    private fun loadOrCreateKeys(context: Context): AdbKeyPair {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }
}

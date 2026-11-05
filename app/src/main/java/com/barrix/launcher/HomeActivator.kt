package com.barrix.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Activa el servicio HomeService conectándose por ADB al propio Fire TV.
 * Requisito: "Depuración ADB" activada en Opciones de desarrollador.
 * La primera vez la tele muestra un aviso de ADB: hay que aceptarlo con el mando.
 *
 * Reintenta durante ~60 s: así da tiempo a aceptar el aviso y, si la tele cierra la primera
 * conexión (EOFException) justo al autorizar la clave, el siguiente intento ya entra.
 */
object HomeActivator {

    private const val PKG = "com.barrix.launcher"
    private const val SERVICE = "$PKG/$PKG.HomeService"
    private const val TOTAL_MS = 60_000L
    private const val RETRY_MS = 2_000L

    sealed class Result {
        object Ok : Result()
        data class Error(val message: String) : Result()
    }

    /** onProgress se llama en el hilo principal mientras espera/reintenta. */
    fun activate(
        context: Context,
        onProgress: ((String) -> Unit)? = null,
        onResult: (Result) -> Unit
    ) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = try {
                run(app) { msg -> if (onProgress != null) main.post { onProgress(msg) } }
            } catch (e: Exception) {
                Result.Error(friendly(e.message ?: e.javaClass.simpleName))
            }
            main.post { onResult(result) }
        }.start()
    }

    /** localhost primero; si falla, la IP de la propia tele en la red local. */
    private fun hosts(): List<String> {
        val out = ArrayList<String>()
        out.add("localhost")
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in ifs) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress
                        if (ip != null) out.add(ip)
                    }
                }
            }
        } catch (e: Exception) { }
        return out
    }

    private fun run(context: Context, progress: (String) -> Unit): Result {
        val keyPair = loadOrCreateKeys(context)
        val deadline = SystemClock.elapsedRealtime() + TOTAL_MS
        var lastError = ""
        var attempt = 0
        while (true) {
            attempt++
            for (host in hosts()) {
                try {
                    Dadb.create(host, 5555, keyPair).use { adb -> return enable(adb) }
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) break
            progress("Acepta el aviso de depuración en la tele (marca «Permitir siempre»)… intento $attempt")
            try { Thread.sleep(RETRY_MS) } catch (e: InterruptedException) { break }
        }
        return Result.Error(friendly(lastError))
    }

    /** Convierte errores técnicos en una instrucción que entienda cualquiera. */
    private fun friendly(raw: String): String {
        val r = raw.lowercase()
        return when {
            "refused" in r ->
                "La tele no admite conexiones ADB. Activa Ajustes > Mi Fire TV > Opciones para desarrolladores > Depuración ADB."
            "eof" in r ->
                "La tele cerró la conexión. Acepta el aviso con «Permitir siempre»; si no sale, desactiva y vuelve a activar la Depuración ADB."
            "timeout" in r || "timed out" in r || "unreachable" in r ->
                "No hubo respuesta de la tele. Comprueba que la Depuración ADB está activada y que hay Wi-Fi."
            else -> raw
        }
    }

    private fun enable(adb: Dadb): Result {
        // 1. Añadir el servicio sin borrar los que ya existan
        val current = adb.shell("settings get secure enabled_accessibility_services")
            .output.trim()
        val list = current.split(":").filter { it.isNotBlank() && it != "null" }
        if (SERVICE !in list) {
            val newValue = (list + SERVICE).joinToString(":")
            adb.shell("settings put secure enabled_accessibility_services '$newValue'")
        }
        adb.shell("settings put secure accessibility_enabled 1")

        // 2. Verificar
        val check = adb.shell("settings get secure enabled_accessibility_services").output
        return if (SERVICE in check) Result.Ok
        else Result.Error("El sistema no guardó el servicio.")
    }

    private fun loadOrCreateKeys(context: Context): AdbKeyPair {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }
}

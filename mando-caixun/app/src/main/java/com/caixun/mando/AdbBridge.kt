package com.caixun.mando

import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/** Conexion ADB por WiFi con el TV (puerto 5555). */
object AdbBridge {
    private var dadb: Dadb? = null

    @JvmStatic
    fun connect(dir: File, host: String) {
        close()
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) {
            AdbKeyPair.generate(priv, pub)
        }
        val keyPair = AdbKeyPair.read(priv, pub)
        val d = Dadb.create(host, 5555, keyPair)
        d.shell("echo ok") // aqui el TV pide permiso la primera vez
        dadb = d
    }

    @JvmStatic
    fun shell(cmd: String): String {
        val d = dadb ?: throw IllegalStateException("Sin conexion WiFi")
        return d.shell(cmd).output
    }

    @JvmStatic
    fun isConnected(): Boolean = dadb != null

    @JvmStatic
    fun close() {
        try {
            dadb?.close()
        } catch (e: Exception) {
        }
        dadb = null
    }
}

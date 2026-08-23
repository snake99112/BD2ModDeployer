package com.example.bd2moddeployer

import android.content.Context
import android.os.IBinder
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

object ShizukuHelper {

    private var isShizukuAvailable = false

    fun init(context: Context) {
        // 检查 Shizuku 是否可用
        isShizukuAvailable = try {
            Shizuku.pingBinder()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isAvailable(): Boolean {
        return isShizukuAvailable
    }

    fun runAsShell(command: String): String? {
        if (!isShizukuAvailable) return null
        
        return try {
            // 通过 Shizuku 执行 shell 命令
            val result = StringBuilder()
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    result.append(line).append("\n")
                }
            }
            
            process.waitFor()
            result.toString()
        } catch (e: Exception) {
            e.message
        }
    }

    fun getVersion(): Int {
        return try {
            Shizuku.getVersion()
        } catch (e: Exception) {
            -1
        }
    }

    fun addPermissionResultCallback(callback: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.addRequestPermissionResultListener(callback)
    }

    fun removePermissionResultCallback(callback: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(callback)
    }
}

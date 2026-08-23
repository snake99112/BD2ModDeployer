package com.example.bd2moddeployer

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuHelper {

    private var isShizukuAvailable = false

    fun init(context: Context) {
        isShizukuAvailable = checkRootAccess()
    }

    private fun checkRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo test"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            process.waitFor()
            output == "test"
        } catch (e: Exception) {
            false
        }
    }

    fun isAvailable(): Boolean {
        return isShizukuAvailable
    }

    fun hasPermission(): Boolean {
        return isShizukuAvailable
    }

    fun requestPermission() {
        // 对于 root 方式，不需要请求权限，静默执行
        // 这里保留空实现以保持接口兼容
    }

    fun runAsShell(command: String): String? {
        if (!isShizukuAvailable) return null
        
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val result = StringBuilder()
            
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    result.append(line).append("\n")
                }
            }
            
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    result.append("[ERR] ").append(line).append("\n")
                }
            }
            
            process.waitFor()
            result.toString()
        } catch (e: Exception) {
            e.message
        }
    }

    fun getVersion(): Int {
        return if (isShizukuAvailable) 1 else -1
    }
}

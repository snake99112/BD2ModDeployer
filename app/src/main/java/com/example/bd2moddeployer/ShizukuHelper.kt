package com.example.bd2moddeployer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku + Root 双通道辅助。
 * - Shizuku 在线且已授权：用 Shizuku 的 UserService 执行 shell
 * - 否则若 Root 可用：用 su 执行
 * - 都不可用：返回 null
 */
object ShizukuHelper {

    var shizukuReady = false
    var rootReady = false
    private var permRequested = false

    fun init(ctx: Context) {
        try {
            shizukuReady = Shizuku.pingBinder()
        } catch (_: Throwable) { shizukuReady = false }
        try {
            rootReady = checkRoot()
        } catch (_: Throwable) { rootReady = false }
    }

    fun isShizukuAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun isRootAvailable(): Boolean = rootReady

    fun hasPermission(): Boolean {
        return if (isShizukuAvailable()) {
            try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) { false }
        } else true // root 通道不需要运行时权限
    }

    fun requestPermission() {
        if (!isShizukuAvailable()) return
        try {
            // Shizuku 13+ 新 API：requestPermission(int, OnRequestPermissionResultListener)
            Shizuku.requestPermission(1001, object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(code: Int, result: Int) {
                    permRequested = (code == 1001)
                }
            })
        } catch (_: Throwable) {
            // 旧版 API 兼容
            try { Shizuku::class.java.getMethod("requestPermission", Int::class.javaPrimitiveType)
                .invoke(null, 1001) } catch (_: Throwable) { }
        }
    }

    /**
     * 执行 shell 命令，返回 (stdout, stderr, success)。
     * 优先 Shizuku，其次 Root。
     */
    fun run(cmd: String): Triple<String, String, Boolean>? {
        // 尝试 Shizuku
        if (isShizukuAvailable() && hasPermission()) {
            try {
                val res = runShizuku(cmd)
                if (res != null) return res
            } catch (_: Throwable) { }
        }
        // 尝试 Root
        if (rootReady) {
            try {
                val res = runSu(cmd)
                if (res != null) return res
            } catch (_: Throwable) { }
        }
        return null
    }

    private fun runShizuku(cmd: String): Triple<String, String, Boolean>? {
        // Shizuku 13+ 通过 SystemServiceHelper 获取 shell 用户服务
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exit = process.waitFor()
            Triple(stdout, stderr, exit == 0)
        } catch (t: Throwable) {
            null
        }
    }

    private fun runSu(cmd: String): Triple<String, String, Boolean>? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exit = process.waitFor()
            Triple(stdout, stderr, exit == 0)
        } catch (t: Throwable) {
            null
        }
    }

    private fun checkRoot(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exit = proc.waitFor()
            exit == 0
        } catch (_: Throwable) { false }
    }
}

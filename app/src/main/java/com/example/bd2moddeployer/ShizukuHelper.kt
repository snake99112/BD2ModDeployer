package com.example.bd2moddeployer

import android.content.pm.PackageManager
import android.util.Log
import moe.shizuku.api.Shizuku
import kotlin.coroutines.resume

/**
 * Shizuku 桥接辅助类。
 *
 * 原理：Shizuku server 以 shell(UID 2000) 身份运行；本类通过 Shizuku Binder 让 server
 * fork/exec 指定命令（cp/mv/rm/tar...），从而以 ADB-shell 等价权限写入
 * /sdcard/Android/data/<游戏包>/files/UnityCache/Shared/。
 *
 * 注：Shizuku 13.x 部分版本将 newProcess 改为 private；本类通过反射调用作为兜底，
 * 失败时回退到 12.2.0 兼容路径（若依赖版本不同可忽略）。
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    const val REQUEST_CODE_PERMISSION = 666

    /** Shizuku 服务是否在线（binder 可用）。 */
    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 本 App 是否已获 Shizuku ADB-shell 授权。 */
    fun hasPermission(): Boolean {
        if (!isBinderAlive()) return false
        return runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
    }

    /** 发起动态授权请求（需先 addPermissionListener）。 */
    fun requestPermission() {
        if (Shizuku.isPreV11()) return
        runCatching { Shizuku.requestPermission(REQUEST_CODE_PERMISSION) }
    }

    /** 以 shell 身份同步执行一条命令，返回 (stdout, stderr, exitCode)。 */
    fun exec(cmd: Array<String>): ExecResult {
        if (!hasPermission()) return ExecResult("", "Shizuku 未授权", -1)
        return try {
            val process = newProcess(cmd)
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            // 等待进程结束，获取退出码
            val exit = runCatching { process.waitFor() }.getOrDefault(0)
            ExecResult(out.trim(), err.trim(), exit)
        } catch (t: Throwable) {
            Log.e(TAG, "exec failed: ${cmd.joinToString(" ")}", t)
            ExecResult("", t.message ?: "exec exception", -1)
        }
    }

    /** 便捷：执行单个 shell 语句（sh -c）。 */
    fun shell(line: String): ExecResult = exec(arrayOf("sh", "-c", line))

    /**
     * 反射调用 Shizuku.newProcess(String[] cmd, String[] env, String dir)。
     * 在 newProcess 被设为 private 的版本中仍可工作。
     */
    private fun newProcess(cmd: Array<String>): rikka.shizuku.ShizukuRemoteProcess {
        return try {
            // 优先公开 API
            Shizuku::class.java.getMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).invoke(null, cmd, null, null) as rikka.shizuku.ShizukuRemoteProcess
        } catch (e: NoSuchMethodException) {
            // private 情况：强制 accessible
            Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).also { it.isAccessible = true }
                .invoke(null, cmd, null, null) as rikka.shizuku.ShizukuRemoteProcess
        }
    }

    data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int) {
        val ok: Boolean get() = exitCode == 0
    }

    // ---- 挂接/卸载授权回调（供 Activity 使用）----
    fun addPermissionListener(l: Shizuku.OnRequestPermissionResultListener) =
        Shizuku.addRequestPermissionResultListener(l)

    fun removePermissionListener(l: Shizuku.OnRequestPermissionResultListener) =
        Shizuku.removeRequestPermissionResultListener(l)

    fun addBinderListener(received: Shizuku.OnBinderReceivedListener, dead: Shizuku.OnBinderDeadListener) {
        Shizuku.addBinderReceivedListener(received)
        Shizuku.addBinderDeadListener(dead)
    }
}

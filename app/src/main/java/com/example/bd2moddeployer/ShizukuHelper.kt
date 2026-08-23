package com.example.bd2moddeployer

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

/**
 * 双通道执行助手：
 *  - 优先 Shizuku（adb/shell 身份，免 Root）执行 shell 命令
 *  - Shizuku 不可用/未授权时回落到 Root (su)
 *
 * 说明：
 *  Shizuku 13.x 的 newProcess 为 private，本类通过反射调用，
 *  避免在 MainActivity 中直接 import 已不可见的 API。
 */
object ShizukuHelper {
    const val PERM_CODE = 1001

    @Volatile private var shizukuReady = false
    @Volatile private var rootReady = false

    // ---------- 初始化 ----------
    fun init(ctx: Context) {
        try {
            Shizuku.addBinderReceivedListenerSticky(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    shizukuReady = Shizuku.pingBinder()
                }
            })
            Shizuku.addBinderDeadListener(object : Shizuku.OnBinderDeadListener {
                override fun onBinderDead() { shizukuReady = false }
            })
            shizukuReady = Shizuku.pingBinder()
        } catch (e: Throwable) {
            shizukuReady = false
        }
        rootReady = checkRoot()
    }

    private fun checkRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo ok"))
        p.inputStream.bufferedReader().readLine() == "ok"
    } catch (_: Throwable) { false }

    // ---------- 状态查询 ----------
    fun isShizukuAvailable(): Boolean = shizukuReady
    fun isRootAvailable(): Boolean = rootReady

    fun hasPermission(): Boolean =
        if (shizukuReady) Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        else rootReady

    fun requestPermission() {
        if (shizukuReady && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(PERM_CODE)
        }
    }

    // ---------- 核心：执行 shell ----------
    /**
     * 返回 Triple(stdout, stderr, exitOk)；
     * 优先 Shizuku（免 root），否则 root，都没有返回 null。
     */
    fun run(cmd: String): Triple<String?, String?, Boolean>? {
        if (shizukuReady && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            try {
                val p = newProcess(arrayOf("sh", "-c", cmd))
                val out = p.inputStream.bufferedReader().use { it.readText() }
                val err = p.errorStream.bufferedReader().use { it.readText() }
                p.waitFor()
                return Triple(out, err, true)
            } catch (e: Throwable) { /* fallthrough to root */ }
        }
        if (rootReady) {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val err = p.errorStream.bufferedReader().use { it.readText() }
            p.waitFor()
            return Triple(out, err, true)
        }
        return null
    }

    /** 反射调用 private Shizuku.newProcess(String[] command, String[] env, String dir)。 */
    private fun newProcess(args: Array<String>): Process {
        val m: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, args, null, null) as Process
    }
}

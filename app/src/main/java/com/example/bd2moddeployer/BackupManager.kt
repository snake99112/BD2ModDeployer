package com.example.bd2moddeployer

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份管理器。
 *
 * 策略：每次部署（覆盖）前，对"将被替换的游戏子目录"做一次性备份到应用私有目录
 *   /data/data/<本应用>/files/backups/<yyyyMMdd_HHmmss>/<相对子目录>...
 * 并记录 manifest.json（含游戏包、各备份子目录相对路径、时间）。
 * 后期可一键恢复：把备份目录 cp 回游戏 UnityCache/Shared 对应位置。
 *
 * 说明：游戏目录需 shell 权限访问，故备份/恢复均通过 ShizukuHelper 执行 cp -a。
 */
class BackupManager(private val ctx: Context) {

    private val gson = Gson()
    private val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** 应用私有备份根目录。 */
    fun backupsRoot(): File = File(ctx.filesDir, "backups").also { it.mkdirs() }

    /** 当前备份清单（每个备份槽 = 一个目录，含 manifest）。 */
    fun listBackups(): List<BackupSlot> {
        val root = backupsRoot()
        return root.listFiles()
            ?.filter { it.isDirectory && File(it, MANIFEST).exists() }
            ?.mapNotNull { slotDir ->
                runCatching { gson.fromJson(File(slotDir, MANIFEST).readText(), BackupSlot::class.java) }
                    .getOrNull()?.copy(slotDir = slotDir.absolutePath)
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /** 对给定"将被覆盖的游戏子目录相对路径列表"执行备份。relativeDirs 如 ["char0001","stage02"] */
    fun backup(relativeDirs: List<String>, gameRoot: String): BackupSlot {
        val slot = BackupSlot(
            name = "backup_${sdf.format(Date())}",
            createdAt = System.currentTimeMillis(),
            gamePackage = ctx.getString(R.string.target_package),
            slotDir = "",
            entries = relativeDirs.map { BackupEntry(it) }
        )
        val slotDir = File(backupsRoot(), slot.name).also { it.mkdirs() }
        slot.entries.forEach { entry ->
            val src = "$gameRoot/$SHARED_REL/$entry.relativePath".trimEnd('/')
            val dst = File(slotDir, entry.relativePath).parentFile!!
            dst.mkdirs()
            // cp -a 保留属性；若源不存在则跳过（首次部署时无原文件）
            val result = ShizukuHelper.runAsShell("cp -a '$src'/ '$dst/' 2>/dev/null || true")
            entry.backupSuccess = result != null && !result.contains("[ERR]")
        }
        // 注意：slot 是 val，不能重新赋值。直接使用 slotDir 路径写入 manifest
        File(slotDir, MANIFEST).writeText(gson.toJson(slot.copy(slotDir = slotDir.absolutePath)))
        return slot
    }

    /** 将指定备份槽恢复到游戏目录（覆盖当前文件）。 */
    fun restore(slot: BackupSlot, gameRoot: String): Boolean {
        val slotDir = File(slot.slotDir)
        if (!slotDir.exists()) return false
        var allOk = true
        slot.entries.forEach { entry ->
            val src = File(slotDir, entry.relativePath).absolutePath
            val dst = "$gameRoot/$SHARED_REL/${File(entry.relativePath).parent ?: ""}".trimEnd('/')
            if (File(src).exists()) {
                val result = ShizukuHelper.runAsShell("mkdir -p '$dst' && cp -a '$src'/ '$dst/'")
                if (result == null || result.contains("[ERR]")) allOk = false
            }
        }
        return allOk
    }

    /** 删除某备份槽。 */
    fun delete(slot: BackupSlot): Boolean = runCatching { File(slot.slotDir).deleteRecursively() }.getOrDefault(false)

    companion object {
        const val MANIFEST = "manifest.json"
        const val SHARED_REL = "files/UnityCache/Shared"
    }

    data class BackupSlot(
        val name: String,
        val createdAt: Long,
        val gamePackage: String,
        val slotDir: String,
        val entries: List<BackupEntry>
    ) {
        fun formattedTime(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(createdAt))
    }

    data class BackupEntry(val relativePath: String, var backupSuccess: Boolean = false)
}

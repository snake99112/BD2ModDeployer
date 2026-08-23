package com.example.bd2moddeployer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份管理器：部署前备份将被替换的游戏目录到内部存储备份区。
 * 备份路径：/storage/emulated/0/Android/data/<this_pkg>/files/backups/<slot>/
 */
class BackupManager(private val ctx: Context) {

    data class BackupEntry(val name: String, val backupSuccess: Boolean)
    data class BackupSlot(val name: String, val time: Long, val entries: List<BackupEntry>, val dir: File) {
        fun formattedTime(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))
    }

    private val baseDir: File
        get() = File(ctx.getExternalFilesDir(null), "backups").also { it.mkdirs() }

    /** 备份指定目录项（modNames）从游戏根目录。返回备份槽。 */
    fun backup(modNames: List<String>, gameRoot: String): BackupSlot {
        val slotName = "backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        val slotDir = File(baseDir, slotName).also { it.mkdirs() }
        val entries = modNames.map { name ->
            val src = "$gameRoot/files/UnityCache/Shared/$name"
            val dst = File(slotDir, name)
            val ok = ShizukuHelper.run("cp -a '$src' '${dst.absolutePath}'")?.third ?: false
            BackupEntry(name, ok)
        }
        return BackupSlot(slotName, System.currentTimeMillis(), entries, slotDir)
    }

    /** 从备份槽恢复到游戏目录。 */
    fun restore(slot: BackupSlot, gameRoot: String): Boolean {
        var allOk = true
        slot.entries.forEach { entry ->
            if (!entry.backupSuccess) return@forEach
            val src = File(slot.dir, entry.name).absolutePath
            val dst = "$gameRoot/files/UnityCache/Shared/${entry.name}"
            val ok = ShizukuHelper.run("rm -rf '$dst' && cp -a '$src' '$dst'")?.third ?: false
            if (!ok) allOk = false
        }
        return allOk
    }

    fun listBackups(): List<BackupSlot> {
        val dir = baseDir
        return dir.listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
            val manifest = File(d, "manifest.txt")
            // 简单解析：目录名即槽名，时间取目录最后修改
            val entries = d.listFiles()?.filter { it.isDirectory }?.map { BackupEntry(it.name, true) } ?: emptyList()
            BackupSlot(d.name, d.lastModified(), entries, d)
        }?.sortedByDescending { it.time } ?: emptyList()
    }

    fun delete(slot: BackupSlot): Boolean = slot.dir.deleteRecursively()
}

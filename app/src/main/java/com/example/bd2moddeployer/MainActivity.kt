package com.example.bd2moddeployer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.example.bd2moddeployer.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main)
    private val backupMgr by lazy { BackupManager(this) }

    /** 游戏目标目录（固定路径） */
    private val gameTargetDir = "/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared"

    /** Mod 源目录（SAF 树 URI） */
    private var modSourceTree: DocumentFile? = null

    /** 用户已授权的游戏目标目录（SAF 树 URI），用于纯 SAF 通道 */
    private var gameTargetTree: DocumentFile? = null

    private val pickModTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        modSourceTree = DocumentFile.fromTreeUri(this, uri)
        binding.tvSelected.text = "源：${uri.path?.substringAfterLast("/") ?: uri.path}"
    }

    private val pickGameTargetTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        gameTargetTree = DocumentFile.fromTreeUri(this, uri)
        log("已设置游戏目标目录：${uri.path?.substringAfterLast("/") ?: uri.path}")
        binding.tvBackupPath.text = "目标：${uri.path?.substringAfterLast("/") ?: uri.path}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全局崩溃捕获（不递归）
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "BD2ModDeployer_crash_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.log"
                )
                FileWriter(crashFile).use { fw ->
                    PrintWriter(fw).use { pw ->
                        pw.println("=== CRASH REPORT ===")
                        pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                        pw.println("Thread: ${thread.name}")
                        pw.println("Message: ${throwable.message}")
                        pw.println("Stack trace:")
                        throwable.printStackTrace(pw)
                        var cause = throwable.cause
                        var depth = 0
                        while (cause != null && depth < 10) {
                            pw.println("\nCaused by: ${cause.message}")
                            cause.printStackTrace(pw)
                            cause = cause.cause
                            depth++
                        }
                    }
                }
                Log.e("BD2Deployer", "Crash written to ${crashFile.absolutePath}", throwable)
            } catch (_: Exception) { }
            Process.killProcess(Process.myPid())
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ShizukuHelper.init(this)

        binding.btnPickMod.setOnClickListener { pickModTree.launch(null) }
        binding.btnDeploy.setOnClickListener { onDeploy() }
        binding.btnBackupRestore.setOnClickListener { showBackupRestoreDialog() }
        binding.btnPickTarget.setOnClickListener { pickGameTargetTree.launch(null) }

        ensureStoragePermission()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // 恢复已保存的目标目录 URI
        if (gameTargetTree == null) {
            val saved = getSharedPreferences("deploy", MODE_PRIVATE).getString("game_target_uri", null)
            if (saved != null) {
                try {
                    gameTargetTree = DocumentFile.fromTreeUri(this, Uri.parse(saved))
                } catch (_: Exception) { }
            }
        }
        binding.tvBackupPath.text = if (gameTargetTree != null) {
            "目标：${gameTargetTree!!.uri.path?.substringAfterLast("/") ?: gameTargetTree!!.uri.path}"
        } else {
            "目标：未设置（将使用 Shizuku/Root 通道）"
        }
    }

    override fun onPause() {
        super.onPause()
        // 持久化目标目录 URI
        gameTargetTree?.let {
            getSharedPreferences("deploy", MODE_PRIVATE).edit()
                .putString("game_target_uri", it.uri.toString()).apply()
        }
    }

    // ====================== 状态刷新 ======================
    private fun refreshStatus() {
        val shizukuOnline = ShizukuHelper.isShizukuAvailable()
        val rootAvailable = ShizukuHelper.isRootAvailable()
        val granted = ShizukuHelper.hasPermission()

        val sb = StringBuilder()
        if (shizukuOnline) {
            sb.append("Shizuku 在线")
            sb.append(if (granted) " · 已授权" else " · 未授权")
        } else {
            sb.append("Shizuku 离线")
        }
        if (rootAvailable) sb.append(" · Root 可用")
        log(sb.toString())
    }

    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    // ====================== 部署流程 ======================
    private fun onDeploy() {
        if (modSourceTree == null) {
            toast("请先选择 Mod 源文件夹")
            return
        }

        scope.launch {
            // 扫描源目录
            val modDirs = withContext(Dispatchers.IO) {
                modSourceTree!!.listFiles()
                    ?.filter { it.isDirectory }
                    ?.map { it.name ?: "" }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()
            }
            if (modDirs.isEmpty()) {
                toast("Mod 源文件夹内没有子目录")
                return@launch
            }
            log("发现 ${modDirs.size} 个 Mod 目录：${modDirs.joinToString(", ")}")

            // 先尝试纯 SAF 通道
            val targetTree = gameTargetTree
            if (targetTree != null) {
                log("使用 SAF 通道部署...")
                val ok = deployViaSaf(targetTree, modDirs)
                if (ok) {
                    toast("部署完成（SAF）")
                    return@launch
                }
                log("SAF 通道写入失败，尝试 Shizuku/Root 通道...")
            }

            // SAF 未设置或失败 -> 走 Shizuku/Root
            if (!ShizukuHelper.hasPermission()) {
                if (ShizukuHelper.isShizukuAvailable()) {
                    promptShizukuAuth()
                } else if (ShizukuHelper.isRootAvailable()) {
                    toast("使用 Root 权限执行")
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("需要更高权限")
                        .setMessage("纯 SAF 方式写入游戏目录失败（权限不足）。\n\n" +
                                "请通过以下方式之一授权：\n" +
                                "① 点击「选择目标目录」手动指定游戏 Shared 目录\n" +
                                "② 安装并启动 Shizuku（推荐，免 Root）\n" +
                                "③ 确保设备已 Root\n\n" +
                                "授权后再次点击部署。")
                        .setPositiveButton("去授权 Shizuku") { _, _ ->
                            ShizukuHelper.requestPermission()
                            try { startActivity(packageManager.getLaunchIntentForPackage("moe.shizuku.manager")) } catch (_: Exception) {}
                        }
                        .setNegativeButton("选择目标目录") { _, _ -> pickGameTargetTree.launch(null) }
                        .setNeutralButton("取消", null)
                        .show()
                    return@launch
                }
                return@launch
            }

            deployViaShell(modDirs)
        }
    }

    /**
     * 纯 SAF 通道：用 DocumentFile API 逐个复制文件/目录到目标树。
     */
    private suspend fun deployViaSaf(targetTree: DocumentFile, modDirs: List<String>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                var success = 0
                modDirs.forEachIndexed { idx, name ->
                    val srcDir = modSourceTree!!.findFile(name) ?: return@forEachIndexed
                    // 在目标树下创建/获取同名目录
                    var dstDir = targetTree.findFile(name)
                    if (dstDir == null) {
                        dstDir = targetTree.createDirectory(name)
                    }
                    if (dstDir == null || !dstDir.isDirectory) {
                        log("SAF: 无法在目标创建目录 $name")
                        return@forEachIndexed
                    }
                    copyDocumentRecursively(srcDir, dstDir)
                    success++
                    runOnUiThread { binding.tvProgress.text = "进度：${idx + 1}/${modDirs.size}" }
                }
                log("SAF 部署完成：$success/${modDirs.size}")
                true
            } catch (t: Throwable) {
                Log.w("BD2Deployer", "SAF deploy failed", t)
                false
            }
        }
    }

    /** 递归复制 DocumentFile 目录内容 */
    private fun copyDocumentRecursively(src: DocumentFile, dst: DocumentFile) {
        src.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val sub = dst.createDirectory(child.name ?: return@forEach) ?: return@forEach
                copyDocumentRecursively(child, sub)
            } else {
                // 文件：通过输入输出流复制
                val outName = child.name ?: return@forEach
                val existing = dst.findFile(outName)
                existing?.delete()
                val outFile = dst.createFile(child.type ?: "application/octet-stream", outName) ?: return@forEach
                try {
                    contentResolver.openInputStream(child.uri)?.use { input ->
                        contentResolver.openOutputStream(outFile.uri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("BD2Deployer", "copy file failed: $outName", t)
                }
            }
        }
    }

    /**
     * Shell 通道（Shizuku/Root）：用 cp 命令复制。
     */
    private suspend fun deployViaShell(modDirs: List<String>) {
        log("使用 Shizuku/Root 通道部署...")
        // 备份
        val slot = withContext(Dispatchers.IO) {
            backupMgr.backup(modDirs, "/storage/emulated/0/Android/data/com.neowizgames.game.browndust2")
        }
        log("备份完成：${slot.name}")

        var success = 0
        modDirs.forEachIndexed { idx, name ->
            val srcDir = modSourceTree!!.findFile(name) ?: return@forEachIndexed
            val realPath = realPathFromTreeUri(srcDir)
            if (realPath.isNullOrBlank()) {
                log("无法解析路径：$name")
                return@forEachIndexed
            }
            val targetPath = "$gameTargetDir/$name"
            val cmd = "rm -rf '$targetPath' && cp -a '$realPath' '$gameTargetDir/'"
            val res = ShizukuHelper.run(cmd)
            if (res != null && res.third) success++
            runOnUiThread { binding.tvProgress.text = "进度：${idx + 1}/${modDirs.size}" }
        }
        log("Shell 部署完成：$success/${modDirs.size}")
        toast("部署完成（Shell）")
    }

    private fun promptShizukuAuth() {
        AlertDialog.Builder(this)
            .setTitle("需要 Shizuku 授权")
            .setMessage("本应用需要通过 Shizuku 获取文件操作权限。\n\n" +
                    "请按以下步骤操作：\n" +
                    "1. 确保已开启 Shizuku（无线调试 / ADB 启动）\n" +
                    "2. 在弹出的 Shizuku 授权窗口中点击「允许」\n" +
                    "3. 若未弹出，请手动打开 Shizuku App 授权\n\n" +
                    "授权后再次点击部署按钮即可。")
            .setPositiveButton("去授权") { _, _ ->
                ShizukuHelper.requestPermission()
                try { startActivity(packageManager.getLaunchIntentForPackage("moe.shizuku.manager")) } catch (_: Exception) {}
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun realPathFromTreeUri(doc: DocumentFile): String? {
        val p = doc.uri.path ?: return null
        val seg = p.substringAfter("document/", "")
        if (seg.startsWith("primary:")) return "/storage/emulated/0/${seg.removePrefix("primary:")}"
        if (seg.startsWith("/")) return seg
        return null
    }

    // ====================== 备份/恢复管理 ======================
    private fun showBackupRestoreDialog() {
        val slots = backupMgr.listBackups()
        if (slots.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("备份管理")
                .setMessage("暂无备份记录。\n部署 Mod 时会自动备份被替换的目录。")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val items = slots.map { "${it.name}  ·  ${it.formattedTime()}  ·  ${it.entries.size} 项" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("备份/恢复（按时间倒序）")
            .setItems(items) { _, which -> showSlotAction(slots[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSlotAction(slot: BackupManager.BackupSlot) {
        AlertDialog.Builder(this)
            .setTitle(slot.name)
            .setMessage("创建于 ${slot.formattedTime()}\n共 ${slot.entries.size} 个目录项\n路径：${slot.dir.absolutePath}\n\n可一键恢复到游戏目录（覆盖当前文件）。")
            .setPositiveButton("恢复") { _, _ -> restoreSlot(slot) }
            .setNegativeButton("删除") { _, _ -> if (backupMgr.delete(slot)) toast("已删除") else toast("删除失败") }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun restoreSlot(slot: BackupManager.BackupSlot) {
        if (!ShizukuHelper.hasPermission() && gameTargetTree == null) {
            promptShizukuAuth()
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { backupMgr.restore(slot, gameTargetDir) }
            log(if (ok) "已恢复备份 ${slot.name}" else "恢复 ${slot.name} 部分失败")
            toast(if (ok) "恢复完成" else "恢复完成（有错误）")
        }
    }

    // ====================== 工具 ======================
    private fun log(s: String) {
        Log.i("BD2Deployer", s)
        binding.tvLog.append("$s\n")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

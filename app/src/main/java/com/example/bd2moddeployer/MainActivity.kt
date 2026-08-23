package com.example.bd2moddeployer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main)
    private val backupMgr by lazy { BackupManager(this) }

    /** 游戏外部存储根：/storage/emulated/0/Android/data/<包>/ */
    private val gameRoot: String
        get() = "/storage/emulated/0/Android/data/${getString(R.string.target_package)}"

    /** 已选 Mod 源目录的 DocumentFile（SAF 树 URI）。 */
    private var modSourceTree: DocumentFile? = null

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        modSourceTree = DocumentFile.fromTreeUri(this, uri)
        binding.tvSelected.text = "已选源：${modSourceTree?.uri?.lastPathSegment ?: uri.path}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater); setContentView(binding.root)

        // 初始化 Shizuku（双通道：Shizuku 免 root / root 兜底）
        ShizukuHelper.init(this)

        binding.btnPickMod.setOnClickListener { pickTree.launch(null) }
        binding.btnDeploy.setOnClickListener { onDeploy() }
        binding.btnBackupRestore.setOnClickListener { showBackupRestoreDialog() }

        ensureStoragePermission()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume(); refreshStatus()
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

        // Shizuku 在线但未授权时自动发起授权请求
        if (shizukuOnline && !granted) {
            ShizukuHelper.requestPermission()
        }
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
        val src = modSourceTree
        if (src == null) { toast("请先选择 Mod 源文件夹"); return }

        // ---- 权限检查 + 引导 ----
        if (!ShizukuHelper.hasPermission()) {
            if (ShizukuHelper.isShizukuAvailable()) {
                showShizukuGuideDialog()
            } else if (ShizukuHelper.isRootAvailable()) {
                toast("使用 Root 权限执行")
            } else {
                showNoEnvDialog()
            }
            return
        }

        // ---- 有权限，开始部署 ----
        scope.launch {
            val gameShared = "$gameRoot/${BackupManager.SHARED_REL}"

            val entries = withContext(Dispatchers.IO) {
                src.listFiles().orEmpty().map { it.name ?: "" }.filter { it.isNotEmpty() }
            }
            if (entries.isEmpty()) { toast("源目录为空"); return@launch }

            log("开始备份将被替换的 ${entries.size} 项...")
            val slot = withContext(Dispatchers.IO) { backupMgr.backup(entries, gameRoot) }
            log("备份槽：${slot.name}（${slot.entries.count { it.backupSuccess }}/${slot.entries.size} 项有原文件）")

            var fail = 0
            withContext(Dispatchers.IO) {
                src.listFiles()?.forEach { doc ->
                    val name = doc.name ?: return@forEach
                    val real = realPathFromTreeUri(doc)
                    if (real.isNullOrBlank()) { fail++; log("跳过(无法解析路径): $name"); return@forEach }
                    val dst = "$gameShared/$name"
                    val res = ShizukuHelper.run("mkdir -p '$dst' && cp -a '$real' '$dst'")
                    if (res == null || !res.third) { fail++; log("部署失败: $name") }
                    else log("部署: $name ✓")
                }
            }
            log("部署完成（成功 ${entries.size - fail}/$fail 失败）。可在「备份/恢复」中一键还原。")
            toast("部署完成")
        }
    }

    /** SAF DocumentFile 的 tree URI -> 真实文件系统路径（默认源在 /storage/emulated/0 下）。 */
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
                .setPositiveButton("OK", null).show()
            return
        }
        val items = slots.map { "${it.name}  ·  ${it.formattedTime()}  ·  ${it.entries.size} 项" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("备份/恢复（按时间倒序）")
            .setItems(items) { _, which -> showSlotAction(slots[which]) }
            .setNegativeButton("取消", null).show()
    }

    private fun showSlotAction(slot: BackupManager.BackupSlot) {
        AlertDialog.Builder(this)
            .setTitle(slot.name)
            .setMessage("创建于 ${slot.formattedTime()}\n共 ${slot.entries.size} 个目录项\n\n可一键恢复到游戏目录（覆盖当前文件）。")
            .setPositiveButton("恢复到此备份") { _, _ -> restoreSlot(slot) }
            .setNegativeButton("删除该备份") { _, _ ->
                if (backupMgr.delete(slot)) toast("已删除备份 ${slot.name}") else toast("删除失败")
            }
            .setNeutralButton("取消", null).show()
    }

    private fun restoreSlot(slot: BackupManager.BackupSlot) {
        if (!ShizukuHelper.hasPermission()) {
            if (ShizukuHelper.isShizukuAvailable()) showShizukuGuideDialog()
            else if (ShizukuHelper.isRootAvailable()) toast("使用 Root 权限执行")
            else toast("无可用的执行环境")
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { backupMgr.restore(slot, gameRoot) }
            log(if (ok) "已恢复备份 ${slot.name}" else "恢复备份 ${slot.name} 部分失败，请查看日志")
            toast(if (ok) "恢复完成" else "恢复完成（有错误）")
        }
    }

    // ====================== 对话框 ======================
    private fun showShizukuGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要 Shizuku 授权")
            .setMessage("本应用需要通过 Shizuku 获取文件操作权限（免 Root）。\n\n" +
                    "请按以下步骤操作：\n" +
                    "1. 确保已开启 Shizuku（无线调试 / ADB 启动）\n" +
                    "2. 在弹出的 Shizuku 授权窗口中点击「允许」\n" +
                    "3. 若未弹出，请手动打开 Shizuku App，在本应用授权列表中授予权限\n\n" +
                    "授权后再次点击部署按钮即可。")
            .setPositiveButton("去授权") { _, _ ->
                ShizukuHelper.requestPermission()
                try {
                    startActivity(packageManager.getLaunchIntentForPackage("moe.shizuku.manager"))
                } catch (_: Exception) { toast("请手动打开 Shizuku 应用授权") }
            }
            .setNegativeButton("稍后再说", null).show()
    }

    private fun showNoEnvDialog() {
        AlertDialog.Builder(this)
            .setTitle("无可用的执行环境")
            .setMessage("本应用需要以下任一环境才能工作：\n\n" +
                    "① Shizuku（推荐，免 Root）\n" +
                    "   安装 Shizuku App 并通过无线调试启动\n\n" +
                    "② Root 权限\n" +
                    "   设备已 Root 且授权本应用\n\n" +
                    "请配置后再试。")
            .setPositiveButton("了解", null).show()
    }

    // ====================== 工具 ======================
    private fun log(s: String) { Log.i("BD2Deployer", s); binding.tvLog.append("$s\n") }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

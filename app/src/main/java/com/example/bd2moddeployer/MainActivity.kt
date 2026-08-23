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

    private val permListener = Shizuku.OnRequestPermissionResultListener { _ /*reqCode*/, grant ->
        if (grant == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            log("Shizuku 授权成功")
        } else {
            log("Shizuku 授权被拒绝"); toast("请先在 Shizuku App 授予本应用权限")
        }
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { log("Shizuku 服务已连接") }
    private val binderDead = Shizuku.OnBinderDeadListener { log("Shizuku 服务已断开") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater); setContentView(binding.root)

        ShizukuHelper.addPermissionListener(permListener)
        ShizukuHelper.addBinderListener(binderReceived, binderDead)

        binding.btnPickMod.setOnClickListener { pickTree.launch(null) }
        binding.btnDeploy.setOnClickListener { onDeploy() }
        binding.btnBackupRestore.setOnClickListener { showBackupRestoreDialog() }

        ensureStoragePermission()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume(); refreshStatus()
    }

    override fun onDestroy() {
        ShizukuHelper.removePermissionListener(permListener)
        super.onDestroy()
    }

    private fun refreshStatus() {
        val alive = ShizukuHelper.isBinderAlive()
        val granted = ShizukuHelper.hasPermission()
        log("Shizuku: ${if (alive) "在线" else "离线/未启动"}${if (granted) " · 已授权" else " · 未授权"}")
        if (alive && !granted) ShizukuHelper.requestPermission()
    }

    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
        }
    }

    // ====================== 部署流程 ======================
    private fun onDeploy() {
        val src = modSourceTree
        if (src == null) { toast("请先选择 Mod 源文件夹（应含 Shared 下的子目录/文件）"); return }
        if (!ShizukuHelper.hasPermission()) { toast("等待 Shizuku 授权..."); ShizukuHelper.requestPermission(); return }

        scope.launch {
            val gameShared = "$gameRoot/${BackupManager.SHARED_REL}"
            // 1) 扫描源目录顶层子项，作为“将被替换的相对路径”
            val entries = withContext(Dispatchers.IO) { src.listFiles().orEmpty().map { it.name ?: "" }.filter { it.isNotEmpty() } }
            if (entries.isEmpty()) { toast("源目录为空"); return@launch }

            // 2) 先备份这些相对路径（若存在）到新槽
            log("开始备份将被替换的 ${entries.size} 项...")
            val slot = withContext(Dispatchers.IO) { backupMgr.backup(entries, gameRoot) }
            log("备份槽：${slot.name}（${slot.entries.count { it.backupSuccess }}/${slot.entries.size} 项有原文件）")

            // 3) 逐个 cp 源 -> 游戏 Shared
            var fail = 0
            withContext(Dispatchers.IO) {
                src.listFiles()?.forEach { doc ->
                    val name = doc.name ?: return@forEach
                    val srcPath = doc.uri.path ?: return@forEach
                    // 通过 DocumentFile 复制内容到临时可见路径不可行；改用 cp 源树 URI 对应真实路径
                    // SAF 树在 /storage/emulated/0/... 有真实路径时直接 cp；此处用 doc.uri 转真实路径
                    val real = realPathFromTreeUri(doc)
                    if (real.isNullOrBlank()) { fail++; log("跳过(无法解析路径): $name"); return@forEach }
                    val dst = "$gameShared/$name"
                    val r = ShizukuHelper.shell("mkdir -p '$dst' && cp -a '$real' '$dst'")
                    if (!r.ok) { fail++; log("部署失败: $name -> ${r.stderr}") }
                    else log("部署: $name ✓")
                }
            }
            log("部署完成（成功 ${entries.size - fail}/$fail 失败）。可在「备份/恢复」中一键还原。")
            toast("部署完成")
        }
    }

    /**
     * 将 SAF DocumentFile 的 tree URI 解析为真实文件系统路径。
     * 对可移动/SDCard 树会失败返回 null；本场景默认源在 /storage/emulated/0 下。
     */
    private fun realPathFromTreeUri(doc: DocumentFile): String? {
        // DocumentFile.uri.path 形如 /tree/.../document/primary:Mods/char0001
        // 对 primary 卷可直接映射到 /storage/emulated/0/
        val p = doc.uri.path ?: return null
        val seg = p.substringAfter("document/", "")
        if (seg.startsWith("primary:")) return "/storage/emulated/0/${seg.removePrefix("primary:")}"
        // 已为真实路径
        if (seg.startsWith("/")) return seg
        return null
    }

    // ====================== 备份/恢复管理 ======================
    private fun showBackupRestoreDialog() {
        val slots = backupMgr.listBackups()
        if (slots.isEmpty()) { AlertDialog.Builder(this).setTitle("备份管理").setMessage("暂无备份记录。\n部署 Mod 时会自动备份被替换的目录。").setPositiveButton("OK", null).show(); return }
        val items = slots.map { "${it.name}  ·  ${it.formattedTime()}  ·  ${it.entries.size} 项" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("备份/恢复（按时间倒序）")
            .setItems(items) { _, which -> showSlotAction(slots[which]) }
            .setNegativeButton("取消", null).show()
    }

    private fun showSlotAction(slot: BackupManager.BackupSlot) {
        AlertDialog.Builder(this).setTitle(slot.name).setMessage("创建于 ${slot.formattedTime()}\n共 ${slot.entries.size} 个目录项\n\n可一键恢复到游戏目录（覆盖当前文件）。")
            .setPositiveButton("恢复到此备份") { _, _ -> restoreSlot(slot) }
            .setNegativeButton("删除该备份") { _, _ ->
                if (backupMgr.delete(slot)) toast("已删除备份 ${slot.name}") else toast("删除失败")
            }
            .setNeutralButton("取消", null).show()
    }

    private fun restoreSlot(slot: BackupManager.BackupSlot) {
        if (!ShizukuHelper.hasPermission()) { toast("等待 Shizuku 授权..."); ShizukuHelper.requestPermission(); return }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { backupMgr.restore(slot, gameRoot) }
            log(if (ok) "已恢复备份 ${slot.name}" else "恢复备份 ${slot.name} 部分失败，请查看日志")
            toast(if (ok) "恢复完成" else "恢复完成（有错误）")
        }
    }

    // ====================== 工具 ======================
    private fun log(s: String) { Log.i("BD2Deployer", s); binding.tvLog.append("$s\n") }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

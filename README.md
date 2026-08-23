# BD2 Mod Deployer

独立提取自 [BDroid_X](https://github.com/Ark-Repoleved/BDroid_X) 的核心能力：**通过 Shizuku 把 Mod 资源覆盖进布朗尘埃2（BrownDust II）的 Unity 外部缓存目录**，并在此基础上新增 **自动备份 + 一键恢复** 功能。

> 本工具不修改游戏 APK、不 Hook 游戏进程；仅利用「shell(UID 2000) 可写 `/sdcard/Android/data/<游戏包>`」与「Unity 优先加载 `UnityCache/Shared` 热更资源」两点实现免 Root、免 PC 的 Mod 安装。

## 工作原理

| 步骤 | 说明 |
|---|---|
| ① 选源 | 通过 SAF 目录选择器指定 Mod 的 `Shared` 源文件夹（含若干子目录/文件） |
| ② 扫描 | 读取源顶层子项，作为「将被替换的相对路径」列表 |
| ③ 自动备份 | 对每个相对路径，若游戏目录中已存在对应项，先 `cp -a` 备份到应用私有 `files/backups/<时间戳>/` 并记录 `manifest.json` |
| ④ 覆盖部署 | 通过 Shizuku 以 shell 身份执行 `cp -a <源> <游戏Shared>/<名>` 逐项覆盖 |
| ⑤ 恢复 | 「备份/恢复」界面列出所有备份槽，可一键把某槽 cp 回游戏目录，或删除旧槽 |

### Shizuku 桥接原理
- Shizuku server 由 `adb`/`root` 启动，进程身份为 **shell (UID 2000)**。
- App 集成 `dev.rikka.shizuku:api` + `ShizukuProvider`；运行时通过 Binder 检查/请求 ADB-shell 授权。
- `ShizukuHelper` 以**反射**调用 `Shizuku.newProcess(cmd, env, dir)`（兼容 13.x 将该方法置为 private 的版本），fork/exec 出 shell 进程执行 `cp/mv/mkdir`，结果经 Binder 回传。
- 等价手工 adb 命令：
  ```bash
  adb shell cp -rf /sdcard/Download/Mods/Shared/. \
    /sdcard/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/
  ```

## 构建

项目已内置完整 Gradle Wrapper（`gradle/wrapper/gradle-wrapper.jar` + `gradlew` / `gradlew.bat`），**无需 Android Studio 也可直接从命令行构建**；三种方式产物一致。

### 方式 D：一键推仓 + 云端出包（零本地构建）

项目根附 `push_to_github.sh`（Linux/macOS）与 `push_to_github.bat`（Windows）：编辑脚本里的 `YOUR_USER`（你的 GitHub 用户名）与仓库名，先在 GitHub 网页建同名空仓库，然后运行脚本即可完成 `git init → commit → push`。推送触发方式 C 的 Actions 工作流，数分钟后在仓库 **Actions → Build APK** run 页面的 Artifacts 下载 `app-debug.apk`。

> 推送用 HTTPS 时，GitHub 要求输入 **Personal Access Token (PAT)**（Settings → Developer settings → Personal access tokens → 勾 `repo`）而非账户密码；或提前配好 SSH key 并使用 SSH 地址。

### 方式 C：GitHub Actions 云端自动出包（推荐，零本地环境）

项目已配置工作流 `.github/workflows/build-apk.yml`：推送至 `main`/`master` 或发起 PR 时自动构建，也可在 GitHub 仓库页面 **Actions → Build APK → Run workflow** 手动触发（`workflow_dispatch`）。

- 运行环境：`ubuntu-latest` + **JDK 17（Temurin）** + `android-actions/setup-android` 自动装好 Android SDK 与接受 licenses。
- 构建命令：`./gradlew assembleDebug --no-daemon`，与本地完全一致。
- **产物**：构建成功后，APK 以 **Artifact `app-debug-apk`** 形式挂载在对应 workflow run 页面，保留 30 天，点击即可下载 `app-debug.apk`；若未生成则步骤会以 `if-no-files-found: error` 失败并报错。
- Gradle 缓存（`~/.gradle/caches`、`~/.gradle/wrapper`）按 `gradle-wrapper.properties` 等哈希键入，二次构建显著提速。
- 如需构建 **release APK**，把工作流中的 `assembleDebug` 改为 `assembleRelease` 并补充签名 secret（`KEYSTORE_BASE64` / `KEY_ALIAS` / `KEY_PASSWORD` / `STORE_PASSWORD`）即可。

### 方式 A：命令行（推荐，无 IDE）

前置：安装 **JDK 17**（Android 构建要求），确认 `JAVA_HOME` 已设且 `java -version` 为 17；Android SDK 可通过 `ANDROID_HOME` 指向（或 `sdkmanager` 装好 `platforms;android-34` / `build-tools;34.0.0`）。

```bash
# Linux / macOS
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

- 首次运行会由 wrapper 自动下载 **Gradle 8.7** 分发包（约 134MB，需联网；若官方 `services.gradle.org` 受限，可改用腾讯云镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip` 手动下载），随后解析依赖并编译。
- 成功后在 `app/build/outputs/apk/debug/app-debug.apk` 得到可安装包。

#### 离线 / 网络受限构建

若构建机无法访问 `services.gradle.org`，可手动准备分发包，wrapper 会复用而不再下载：

1. 从任意镜像下载 `gradle-8.7-bin.zip`（如腾讯云 `https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip`）。
2. 先运行一次 `./gradlew --version`（会失败于下载），让它生成目录：
   `~/.gradle/wrapper/dists/gradle-8.7-bin/<随机字符串>/`。
3. 把下载好的 `gradle-8.7-bin.zip` 放入该 `<随机字符串>/` 目录，同目录新建空文件 `gradle-8.7-bin.zip.ok`。
4. 重新执行 `./gradlew assembleDebug`，wrapper 将使用本地分发包完成构建。

### 方式 B：Android Studio

1. 用 Android Studio（2024.3+，JDK 17）打开本项目根目录，等待 Gradle 同步。
2. 首次构建需联网拉取 `dev.rikka.shizuku:api:13.1.5` / `provider:13.1.5`（仓库 `https://maven.rikka.app/` 已在 `settings.gradle.kts` 声明）。
3. `Build → Build Bundle(s) / APK(s) → Build APK` 生成同上路径的 APK。

> 若 `newProcess` 反射在更高版本 Shizuku 上失效，可降级依赖至 `12.2.0`（API 稳定公开），见 `app/build.gradle.kts` 的 `shizukuVersion`。

## 使用

1. 设备安装并启动 **Shizuku**（ADB 无线调试或 USB 一次激活；每次重启需重激活）。
2. 授予本应用 Shizuku 权限（首次部署时会自动弹授权请求）。
3. Android 11+ 授予「所有文件访问」权限（首次启动会引导）。
4. 点击 **选择 Mod 源文件夹** → 选含 `Shared` 内容的目录。
5. 点击 **部署 Mod**：自动备份被替换目录 → 覆盖写入游戏 `UnityCache/Shared`。
6. **备份/恢复管理**：列出每次部署产生的备份槽（时间戳 + 项数），可一键恢复或删除。

## 自动备份 / 恢复设计

- **备份位置**：`/data/data/com.example.bd2moddeployer/files/backups/<backup_yyyyMMdd_HHmmss>/<相对子目录>/`
  - 应用私有目录，无需额外存储权限，重装会丢失（可扩展为备份到 `Documents/`）。
- **清单**：每槽 `manifest.json` 记录游戏包名、创建时间、各备份项相对路径与成败，供恢复时精确还原。
- **增量语义**：仅备份「本次将被覆盖」的目录项；首次部署（游戏无对应项）则该项标记无原文件，恢复时会跳过不存在项。
- **恢复**：把槽内各子目录 `cp -a` 回游戏 `UnityCache/Shared` 对应父目录，覆盖当前文件，实现一键还原到该备份时点。

## 注意事项

- Mod 文件名须**全小写**（如 `char000104.png`），`.atlas` 内引用亦小写，否则游戏可能崩溃（沿用 BDroid_X 约束）。
- 本工具仅负责文件搬运与备份；资源合并/转 ASTC/合 Spine 由 BDroid_X 完成，建议先在其内产出 `Shared` 目录再交由本工具部署。
- Shizuku 非 Root，受 SELinux `u:r:shell:s0` 约束；`/sdcard/Android/data` 恰在 shell 允许范围。
- 游戏包名：`com.neowizgames.game.browndust2`，目标路径 `files/UnityCache/Shared`，均在 `strings.xml` / `BackupManager.SHARED_REL` 集中配置，便于改为其他 Unity 游戏。

## 免责声明

仅供学习研究与个人备份恢复用途。Mod 使用请遵守游戏运营方条款；本工具不对游戏账号风险、Mod 兼容性问题负责。

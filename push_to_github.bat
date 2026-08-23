@echo off
REM push_to_github.bat —— Windows 一键推送（先编辑 YOUR_USER / BD2ModDeployer）
set GITHUB_USER=YOUR_USER
set GITHUB_REPO=BD2ModDeployer
set REMOTE=https://github.com/%GITHUB_USER%/%GITHUB_REPO%.git

cd /d "%~dp0"
echo [*] 仓库: %REMOTE%

git remote remove origin 2>nul || true
git remote add origin "%REMOTE%"

git add .
git diff --cached --quiet
if %ERRORLEVEL% neq 0 (
  git commit -m "feat: BD2 mod deployer with shizuku + auto-backup + GH Actions"
)

git branch -M main
echo [*] 推送 main 分支到 origin
git push -u origin main
echo [*] 完成！前往仓库 Actions 页查看 Build APK 工作流。

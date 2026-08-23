#!/usr/bin/env bash
# push_to_github.sh —— 一键把 BD2ModDeployer 推到你的 GitHub 仓库
# 用法：先编辑下方 GITHUB_USER/GITHUB_REPO，或导出环境变量后执行 ./push_to_github.sh
set -e
GITHUB_USER="${GITHUB_USER:-YOUR_USER}"
GITHUB_REPO="${GITHUB_REPO:-BD2ModDeployer}"
REMOTE="https://github.com/${GITHUB_USER}/${GITHUB_REPO}.git"

cd "$(dirname "$0")"
echo "[*] 仓库: ${REMOTE}"

git remote remove origin 2>/dev/null || true
git remote add origin "${REMOTE}"

git add .
if git diff --cached --quiet; then
  echo "[*] 无新提交，跳过 commit"
else
  git commit -m "feat: BD2 mod deployer with shizuku + auto-backup + GH Actions"
fi

git branch -M main
echo "[*] 推送 main 分支到 origin（若用 HTTPS 会要求输入 GitHub Personal Access Token）"
git push -u origin main
echo "[✓] 推送完成！前往仓库 Actions 页查看 Build APK 工作流自动运行。"

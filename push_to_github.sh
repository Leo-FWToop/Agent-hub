#!/bin/bash
# 推送到 GitHub 的脚本

echo "AgentHub GitHub 推送脚本"
echo "========================"

# 检查当前状态
echo "当前 git 状态："
git status
echo ""

# 显示提交历史
echo "最近提交历史："
git log --oneline -5
echo ""

# 提示用户输入 GitHub 凭据
echo "要推送到 GitHub，您需要提供 GitHub 用户名和 Personal Access Token。"
echo "如果没有 Token，请访问：https://github.com/settings/tokens"
echo ""

# 推送
echo "正在推送..."
git push origin master

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ 推送成功！"
    echo "仓库地址：https://github.com/Leo-FWToop/Agent-hub"
else
    echo ""
    echo "❌ 推送失败。可能的原因："
    echo "1. GitHub 用户名或 Token 错误"
    echo "2. 没有仓库的写入权限"
    echo "3. 网络连接问题"
    echo ""
    echo "请检查："
    echo "1. 确保您有 GitHub Personal Access Token"
    echo "2. 确保 Token 有 'repo' 权限"
    echo "3. 确保您是仓库的所有者或有写入权限"
fi
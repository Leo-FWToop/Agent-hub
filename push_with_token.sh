#!/bin/bash
# 使用 Fine-grained Token 推送脚本

echo "AgentHub GitHub 推送脚本"
echo "========================"

# 提示用户输入 token
echo "请输入您的 Fine-grained Token："
echo "(格式：ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx)"
read -p "Token: " TOKEN

if [ -z "$TOKEN" ]; then
    echo "❌ 错误：Token 不能为空"
    exit 1
fi

echo ""
echo "正在设置远程仓库..."
git remote set-url origin "https://Leo-FWToop:$TOKEN@github.com/Leo-FWToop/Agent-hub.git"

echo "正在推送代码..."
git push origin master

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ 推送成功！"
    echo ""
    echo "下一步："
    echo "1. 访问 https://github.com/Leo-FWToop/Agent-hub/actions"
    echo "2. 查看 'Build Android APK' 工作流"
    echo "3. 等待编译完成并下载 APK"
    
    # 清理 token
    echo ""
    echo "正在清理 token..."
    git remote set-url origin https://github.com/Leo-FWToop/Agent-hub.git
    echo "✅ Token 已清理"
else
    echo ""
    echo "❌ 推送失败"
    echo "可能的原因："
    echo "1. Token 权限不足（需要 Contents: Read and write）"
    echo "2. Token 格式错误"
    echo "3. 网络连接问题"
fi
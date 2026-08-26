#!/bin/bash
# GitHub Actions 编译设置脚本

echo "AgentHub GitHub Actions 编译设置"
echo "================================"

# 创建工作流目录
mkdir -p .github/workflows

# 创建工作流文件
cat > .github/workflows/build.yml << 'EOF'
name: Build Android APK

on:
  push:
    branches: [ master, main ]
  pull_request:
    branches: [ master, main ]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
    - name: Checkout code
      uses: actions/checkout@v4

    - name: Set up JDK 17
      uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'

    - name: Setup Android SDK
      uses: android-actions/setup-android@v3

    - name: Grant execute permission for gradlew
      run: chmod +x gradlew

    - name: Build Debug APK
      run: ./gradlew assembleDebug

    - name: Build Release APK
      run: ./gradlew assembleRelease

    - name: Upload Debug APK
      uses: actions/upload-artifact@v4
      with:
        name: debug-apk
        path: app/build/outputs/apk/debug/*.apk
        retention-days: 7

    - name: Upload Release APK
      uses: actions/upload-artifact@v4
      with:
        name: release-apk
        path: app/build/outputs/apk/release/*.apk
        retention-days: 30
EOF

echo "✅ 工作流文件已创建：.github/workflows/build.yml"
echo ""
echo "下一步："
echo "1. 更新您的 GitHub Token 权限（添加 workflow 权限）"
echo "2. 推送工作流文件到 GitHub"
echo "3. 在 GitHub 上查看编译结果"
echo ""
echo "推送命令："
echo "git add .github/workflows/build.yml"
echo "git commit -m '添加 GitHub Actions 工作流'"
echo "git push origin master"
echo ""
echo "或者使用此脚本推送："
echo "chmod +x setup_github_build.sh"
echo "./setup_github_build.sh push"
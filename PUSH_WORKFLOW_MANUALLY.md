# 手动推送 GitHub Actions 工作流

## 当前状态
- 工作流文件已创建：`.github/workflows/build.yml`
- 本地有 3 个提交等待推送
- 网络连接超时，需要手动推送

## 手动推送步骤

### 步骤 1：设置 token
在终端中执行：
```bash
cd /data/data/com.termux/files/home/Agenthub
git remote set-url origin https://Leo-FWToop:YOUR_TOKEN@github.com/Leo-FWToop/Agent-hub.git
```

**请将 `YOUR_TOKEN` 替换为您的实际 token**

### 步骤 2：推送代码
```bash
git push origin master
```

### 步骤 3：清理 token（推送成功后）
```bash
git remote set-url origin https://github.com/Leo-FWToop/Agent-hub.git
```

## 工作流文件内容

`.github/workflows/build.yml` 文件已包含以下内容：

```yaml
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
```

## 推送后验证

### 1. 检查 GitHub 仓库
访问：https://github.com/Leo-FWToop/Agent-hub

### 2. 查看 Actions
1. 点击 "Actions" 标签
2. 查看 "Build Android APK" 工作流
3. 等待编译完成

### 3. 下载 APK
编译完成后：
1. 点击最新的工作流运行
2. 在 "Artifacts" 部分下载：
   - `debug-apk`: Debug 版本
   - `release-apk`: Release 版本

## 故障排除

### 问题 1：推送失败
**错误**: `fatal: could not read Username for 'https://github.com': No such device or address`

**解决方案**:
1. 确保使用正确的 token
2. 检查 token 权限（需要 `repo` 和 `workflow` 权限）
3. 尝试使用 SSH 协议

### 问题 2：工作流未触发
**检查**:
1. 确保文件路径正确：`.github/workflows/build.yml`
2. 确保分支名称正确：`master` 或 `main`
3. 查看 GitHub Actions 日志

### 问题 3：编译失败
**常见原因**:
1. Android SDK 版本不兼容
2. 依赖下载失败
3. 签名配置错误

**解决方案**:
1. 查看 GitHub Actions 详细日志
2. 检查 `app/build.gradle.kts` 配置
3. 确保所有依赖都已正确声明

## 下一步

推送成功后：
1. ✅ GitHub Actions 将自动编译 APK
2. ✅ 您可以在 Actions 页面查看编译进度
3. ✅ 编译完成后可以下载 APK 文件
4. ✅ 可以创建 GitHub Release 发布新版本

## 联系支持

如果遇到问题：
1. 查看 GitHub Actions 日志
2. 检查网络连接
3. 确认 token 权限正确
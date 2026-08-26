# AgentHub 编译指南

## 方法 1：使用 GitHub Actions（推荐）

### 前提条件
1. **更新 GitHub Token 权限**
   - 访问：https://github.com/settings/tokens
   - 编辑您的 Personal Access Token
   - 添加 `workflow` 权限（除了已有的 `repo` 权限）
   - 保存更改

### 步骤 1：添加工作流文件
将以下内容保存为 `.github/workflows/build.yml`：

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

### 步骤 2：推送到 GitHub
```bash
cd /data/data/com.termux/files/home/Agenthub
git add .github/workflows/build.yml
git commit -m "添加 GitHub Actions 工作流"
git push origin master
```

### 步骤 3：查看编译结果
1. 访问：https://github.com/Leo-FWToop/Agent-hub
2. 点击 "Actions" 标签
3. 查看编译进度和结果
4. 编译完成后，在 "Artifacts" 部分下载 APK 文件

## 方法 2：本地编译

### 前提条件
1. 安装 JDK 17
2. 安装 Android SDK
3. 设置环境变量

### 步骤 1：克隆仓库
```bash
git clone https://github.com/Leo-FWToop/Agent-hub.git
cd Agent-hub
```

### 步骤 2：编译 Debug APK
```bash
chmod +x gradlew
./gradlew assembleDebug
```

### 步骤 3：编译 Release APK
```bash
./gradlew assembleRelease
```

### 步骤 4：查找编译结果
- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release APK**: `app/build/outputs/apk/release/app-release.apk`

## 方法 3：使用 Android Studio

### 步骤 1：导入项目
1. 打开 Android Studio
2. 选择 "Open an existing Android Studio project"
3. 选择 AgentHub 目录

### 步骤 2：同步项目
1. 等待 Gradle 同步完成
2. 如果提示更新 SDK，点击 "Update"

### 步骤 3：编译 APK
1. 点击菜单 "Build" -> "Build Bundle(s) / APK(s)" -> "Build APK(s)"
2. 等待编译完成
3. 点击 "locate" 查看 APK 文件

## 编译输出

### APK 文件位置
- **Debug**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release**: `app/build/outputs/apk/release/app-release.apk`

### 签名信息
Release APK 使用以下签名配置：
- **Keystore**: `release.keystore`
- **Alias**: `agenthub`
- **Password**: `agenthub-v1`

## 故障排除

### 问题 1：Gradle 同步失败
**解决方案**：
```bash
# 清理项目
./gradlew clean

# 重新同步
./gradlew build
```

### 问题 2：Android SDK 找不到
**解决方案**：
1. 检查 `local.properties` 文件
2. 确保 `sdk.dir` 指向正确的 Android SDK 路径

### 问题 3：签名错误
**解决方案**：
1. 确保 `release.keystore` 文件存在于项目根目录
2. 检查 `app/build.gradle.kts` 中的签名配置

### 问题 4：内存不足
**解决方案**：
在 `gradle.properties` 中增加内存：
```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
```

## 版本信息
- **当前版本**: v1.15 (versionCode 20)
- **最低 Android 版本**: 8.0 (API 26)
- **目标 Android 版本**: 15 (API 35)
- **编译 SDK**: 35

## 技术支持
如果遇到编译问题：
1. 检查 GitHub Actions 日志（如果使用 GitHub Actions）
2. 查看 Gradle 错误信息
3. 确保所有依赖都已正确下载
# 手动在 GitHub 上创建工作流文件

## 当前状态
- 本地工作流文件已准备好：`.github/workflows/build.yml`
- 自动上传遇到技术问题
- 需要手动在 GitHub 网站上创建文件

## 手动创建步骤

### 步骤 1：访问 GitHub 仓库
1. 打开浏览器，访问：https://github.com/Leo-FWToop/Agent-hub
2. 登录您的 GitHub 账户

### 步骤 2：创建目录结构
1. 点击 "Add file" 按钮
2. 选择 "Create new file"
3. 在文件名输入框中输入：`.github/workflows/build.yml`
   - GitHub 会自动创建 `.github` 和 `workflows` 目录

### 步骤 3：粘贴工作流内容
将以下内容复制并粘贴到文件编辑器中：

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

### 步骤 4：提交文件
1. 在 "Commit new file" 部分：
   - Commit message: `添加 GitHub Actions 工作流：自动编译 Android APK`
   - 选择 "Commit directly to the master branch"
2. 点击 "Commit new file" 按钮

### 步骤 5：验证工作流
1. 提交后，点击仓库顶部的 "Actions" 标签
2. 您应该能看到 "Build Android APK" 工作流
3. 工作流会自动开始编译

## 工作流文件说明

### 触发条件
- **push**: 当代码推送到 `master` 或 `main` 分支时触发
- **pull_request**: 当创建针对 `master` 或 `main` 分支的 Pull Request 时触发

### 编译步骤
1. **Checkout code**: 检出代码
2. **Set up JDK 17**: 设置 Java 17 环境
3. **Setup Android SDK**: 设置 Android SDK
4. **Grant execute permission**: 给 gradlew 执行权限
5. **Build Debug APK**: 编译 Debug 版本
6. **Build Release APK**: 编译 Release 版本
7. **Upload Debug APK**: 上传 Debug APK 作为构件
8. **Upload Release APK**: 上传 Release APK 作为构件

### 编译结果
编译成功后，您可以在以下位置找到 APK 文件：
- **Actions 页面**: 点击最新的工作流运行，在 "Artifacts" 部分下载
- **文件路径**:
  - Debug: `app/build/outputs/apk/debug/app-debug.apk`
  - Release: `app/build/outputs/apk/release/app-release.apk`

## 故障排除

### 问题 1：工作流未触发
**检查**:
1. 确保文件路径正确：`.github/workflows/build.yml`
2. 确保分支名称正确：`master` 或 `main`
3. 查看 "Actions" 页面是否有错误信息

### 问题 2：编译失败
**常见原因**:
1. Android SDK 版本不兼容
2. 依赖下载失败
3. 签名配置错误

**解决方案**:
1. 查看 GitHub Actions 详细日志
2. 检查 `app/build.gradle.kts` 配置
3. 确保所有依赖都已正确声明

### 问题 3：APK 下载失败
**检查**:
1. 确保工作流编译成功（绿色对勾）
2. 检查 "Artifacts" 部分是否有文件
3. 尝试重新运行工作流

## 下一步

### 1. 首次编译
- 工作流创建后会自动开始编译
- 第一次编译可能需要较长时间（下载依赖）
- 编译完成后会自动上传 APK 文件

### 2. 后续更新
- 每次推送到 `master` 分支都会自动编译
- 每次创建 Pull Request 都会自动编译
- 您可以在 "Actions" 页面查看所有编译历史

### 3. 发布版本
- 编译成功后，可以下载 APK 文件
- 可以创建 GitHub Release 发布新版本
- 可以在 Release 中附带编译好的 APK 文件

## 技术支持

如果遇到问题：
1. 查看 GitHub Actions 日志
2. 检查网络连接
3. 确认文件内容正确
4. 联系仓库管理员
# AgentHub GitHub 推送说明

## 当前状态
- 本地有 5 个提交，包含 v1.10 到 v1.15 的所有更新
- 远程仓库：https://github.com/Leo-FWToop/Agent-hub
- 当前分支：master

## 推送方法

### 方法 1：使用 GitHub Personal Access Token（推荐）

#### 步骤 1：创建 GitHub Personal Access Token
1. 访问：https://github.com/settings/tokens
2. 点击 "Generate new token"
3. 选择权限：`repo`（完整仓库访问权限）
4. 生成并复制 Token

#### 步骤 2：配置 Git 凭据
```bash
# 在 Agenthub 目录中执行
cd /data/data/com.termux/files/home/Agenthub

# 设置 GitHub 用户名
git config user.email "your-email@example.com"
git config user.name "Your Name"

# 推送时输入用户名和 Token
git push origin master
```

#### 步骤 3：输入凭据
当提示输入密码时，粘贴您的 Personal Access Token（不是 GitHub 密码）。

### 方法 2：使用 SSH 密钥

#### 步骤 1：生成新的 SSH 密钥
```bash
ssh-keygen -t ed25519 -C "your-email@example.com"
```

#### 步骤 2：复制公钥
```bash
cat ~/.ssh/id_ed25519.pub
```

#### 步骤 3：添加到 GitHub
1. 访问：https://github.com/settings/keys
2. 点击 "New SSH key"
3. 粘贴公钥

#### 步骤 4：更改远程仓库为 SSH
```bash
git remote set-url origin git@github.com:Leo-FWToop/Agent-hub.git
git push origin master
```

## 包含的更新

### v1.15 (versionCode 20)
- [改进] 错误处理：订阅相关错误不重试，提供更清晰的错误提示
- [新增] 订阅错误解决指南：帮助用户解决 CodingPlan 订阅问题

### v1.14 (versionCode 19)
- [修复] 对话混乱问题：添加会话键过滤，防止不同对话的消息交叉
- [修复] 上下文遗忘问题：发送历史对话上下文，保持长对话的连贯性
- [新增] 配置导入功能：设置页「配置持久化」模块新增导入按钮

### v1.13 (versionCode 18)
- [移除] 底部状态栏（模型 / token 用量 / 缓存命中率）
- [新增] 后台回复通知：当应用不在前台时弹出系统通知

### v1.12 (versionCode 17)
- [新增] 上传文件功能：文本类直接嵌入，二进制类转 base64
- [新增] 配置持久化到外部存储：卸载重装后自动恢复配置
- [修复] 等待回复超时问题：超时时间从 5 分钟放宽到 10 分钟

### v1.10 (versionCode 15)
- [新增] 发送图片功能：相册选图→压缩→Base64
- [新增] Agent 回复终止功能
- [新增] 提问修改功能
- [改进] 对话标题自动总结
- [新增] 夜间模式切换并持久化

## 验证推送成功

推送成功后，访问以下链接查看：
https://github.com/Leo-FWToop/Agent-hub

## 故障排除

### 问题 1：权限被拒绝
- 确保您有仓库的写入权限
- 检查 Token 是否有 `repo` 权限

### 问题 2：网络连接问题
- 检查网络连接
- 尝试使用 VPN 或代理

### 问题 3：认证失败
- 确保用户名和 Token 正确
- Token 不能是 GitHub 密码，必须是 Personal Access Token
# OpenClaw 订阅错误解决指南

## 错误信息
```
错误：运行出错：400 Your account (2122358760) does not have a valid CodingPlan subscription, or your subscription has expired. Please visit https://console.volcengine.com/ark/region:ark+cn-beijing/openManagement?OpenModelVisible=false&tab=CodingPlan to rev...
```

## 错误原因
这个错误表明您的 OpenClaw 账户（ID: 2122358760）没有有效的 CodingPlan 订阅，或者订阅已过期。

## 解决方案

### 1. 检查订阅状态
1. 访问火山引擎控制台：https://console.volcengine.com/ark/region:ark+cn-beijing/openManagement?OpenModelVisible=false&tab=CodingPlan
2. 登录您的账户
3. 检查 CodingPlan 订阅状态

### 2. 订阅或续费
如果订阅已过期或未订阅：
1. 在控制台中选择合适的 CodingPlan 套餐
2. 完成订阅或续费流程
3. 确保支付成功

### 3. 验证账户信息
确保您使用的是正确的账户 ID：
1. 在 OpenClaw 设置中检查连接配置
2. 确认令牌（Token）正确无误
3. 如果使用密码认证，确保密码正确

### 4. 重启应用
完成订阅后：
1. 关闭 AgentHub 应用
2. 重新打开应用
3. 尝试发送消息

## 联系支持
如果问题仍然存在：
1. 访问火山引擎技术支持
2. 提供您的账户 ID：2122358760
3. 说明错误信息和已尝试的解决方案

## 注意事项
- 订阅通常需要几分钟时间生效
- 确保网络连接正常
- 检查是否有防火墙或代理限制访问
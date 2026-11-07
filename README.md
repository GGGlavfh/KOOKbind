# KOOK绑定插件  
## 指令
`/kookbind [unbind [玩家]]` 获取绑定验证码，或解除绑定  
## 权限
|权限节点|默认状态|含义|
|-------|--------|---|
|kookbind.unbind.self|ture|是否允许玩家解除自己的绑定（/kookbind unbind）|
|kookbind.unbind.others|op|允许解除任意玩家的绑定|
## PAPI
|占位符|返回值|
|-----|----|
|%kook_bound%|已绑定 true / 未绑定 false|
|%kook_uid%|绑定的 KOOK 用户 id；未绑定为空|

# KOOK_EPbot
## 指令
| 指令 | 说明 | 权限 |
| --- | --- | --- |
| `/help` | 显示帮助信息 | 管理员 |
| `/welcome on` / `/welcome off` | 在**当前频道**开关自动欢迎 | 管理员 |
| `/log on` / `/log off` | 在**当前频道**开关运行播报（每小时一条） | 管理员 |
| `/ticket on` | 在**当前频道**放出发票面板（卡片） | 管理员 |
| `/ticket off` | 关闭本服务器的工单入口 | 管理员 |
| `/bind <验证码>` | 绑定游戏账号，仅限绑定频道 | 所有人 |
| `/bind status <KOOK用户id>` | 查看该用户当前绑定的游戏账号 | 管理员|
| `/bind history <KOOK用户id>` | 查看该用户历史绑定记录（最近 5 条） | 管理员 |
| `/bind channel` | 把当前频道设为绑定频道 | 管理员 |
| `/bind off` | 关闭绑定功能 | 管理员 |

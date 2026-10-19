# 独立聊天会话

一个用户可以拥有多个聊天；一个聊天可以讨论多道力扣题目，不将 `chat_session` 绑定到题目。打开插件或切换后端 / Agent 设置时，先恢复有效的上次聊天，没有有效指针则查询历史并恢复最近一条；确认列表为空才自动创建首个会话。网络 / 数据库 / 格式错误不视为没有历史。除此之外通过“新对话”主动创建，切题和直接发送消息不隐式创建会话。发送消息必须携带已有且属于当前用户、Agent 的 `sessionId`。

## 接口

接口前缀为 `/api/v1`，使用项目现有响应格式 `{ "code": "0000", "info": "成功", "data": ... }`。业务错误为 `0002`，未知错误为 `0001`。

| 方法 | 路径 | 参数 / 返回 |
| --- | --- | --- |
| POST | `/create_session` | JSON：`agentId`、`userId`、可选 `title`；返回 `data.sessionId`，每次均为新 ID |
| GET | `/chat/sessions` | `agentId`、`userId`、`offset=0`、`limit=20`；返回 `sessions`、`hasMore`、`nextOffset` |
| GET | `/chat/session_detail` | `agentId`、`userId`、`sessionId`、可选 `beforeMessageId`、`limit=50`；返回 `session`、`messages`、`hasMore`、`beforeMessageId` |
| POST | `/chat/session_delete` | JSON：`agentId`、`userId`、`sessionId`；确认成功返回 `data: true` |
| POST | `/chat/message` | JSON：`agentId`、`userId`、`sessionId`、`content`；返回保存后的普通助手消息 `id`、`role`、`content`、`createdAt` |

列表按更新时间、ID 倒序排列。历史查询仅返回用户 / 助手消息，按消息 ID 正序展示；传入上一页的 `beforeMessageId` 加载更早消息。`limit` 范围 1～100。默认标题是“新对话”，第一次发送时替换为问题的简短标题。

插件使用原有 `/chat_react_stream` 对话链路；原有 `/chat_stream` 也校验会话归属及运行占用，但没有新增 ReAct 的消息持久化逻辑。要得到数据库完整聊天历史，应使用插件默认的 ReAct 接口。

## 数据与上下文边界

沿用现有 `chat_session`、`chat_message`、`chat_milestone` 表，无需新增字段或索引。数据库必须正常连接，新建会话或保存消息失败会返回错误。

打开插件、读取或切换题目时，会在当前对话末尾追加一条普通 `assistant` 消息，复用已有消息保存流程，不区分连接提示和普通回答。题名和正文保留保存时的内容，后续切题或恢复历史不会改写。提示不调用模型，保存成功后显示，并使用现有消息计数、分页及会话删除机制。

不判断重复连接；同一道题再次读取也直接追加消息。快速切题时，按读取顺序保存这些消息。

- 用户长期记忆：按 `userId` 检索，可以跨会话。
- 对话历史、动态任务、意图历史 / 失败反馈、工具结果摘要、里程碑和 ADK State：只使用当前会话。
- 当前题目 / 代码：每次发送时重新读取网页，是本轮输入；切题不改变 `sessionId`。
- 新会话：空历史、空任务状态，不复制前一个会话的临时上下文。

数据库消息作为历史的持久来源。插件按“后端地址 + Agent + 用户 + sessionId”缓存最近的显示消息，匹配数据库消息计数时可保留本地的 Agent 分组显示。更早消息从数据库分页读取。目前数据库保存的是助手合并回答，不包含完整的 Agent 显示分组；缓存失效、清除插件存储或加载更早历史时，会使用数据库中的合并回答。

后端重启后，再次发送到旧会话时使用相同 ID 恢复最多近期 80 条用户 / 助手文本；现有 ADK 的轮次及条数裁剪仍生效。不会精确重建历史工具调用事件、旧 ADK State 或内存任务态，不从其他会话借历史。

旧版本按题目存的本地记录保留原样，但不自动迁移；缺少数据库会话元数据的历史不会自动收录。删除后的 `sessionId` 不能通过发送消息重新创建。

## 删除及并发

删除先验证归属，再在仓储事务内锁定会话、删除消息及里程碑、最后删除主记录；任何删除失败都回滚。事务提交后清理该会话的 ADK 快照、任务 / 意图状态、里程碑及工具缓存。

长期记忆、错题主记录及复盘记录不删除，错题里的来源会话 ID 仅保留作历史参考。删除不可恢复。开发验证使用模拟仓储，不删除真实用户记录。

同一会话同时只能进行一个聊天 / 删除操作；不同会话可以各自运行。此占用保护位于单 JVM 内存，适用于当前单实例部署；未来多实例需要分布式协调。浏览器取消只停止等待，并不保证模型和子 Agent 已停止；后端实际执行未结束时删除会返回“仍在生成或处理中”。

当前用户身份沿用插件生成的 `extension-...` ID，归属检查不是登录认证。未来上线应从服务端认证身份取得用户 ID，不能把客户端传入的 ID 当作安全身份凭证。

## 使用与验证

1. 数据库保留现有聊天相关表；重新构建并启动后端。
2. 在 Edge 扩展管理页重新加载插件，关闭并重新打开侧边栏。
3. 没有历史时插件会自动创建首个会话；发送消息后再点击“新对话”，验证 ID 不同、旧对话保留。已有历史时打开插件应恢复上次或最近的聊天，不创建多余空会话。
4. 切换力扣题目，验证旧消息不变、末尾追加普通助手消息；重新打开同题也会追加，清除会话显示缓存后仍能从数据库恢复。打开“历史”，切换、分页或确认删除。
5. 新会话不应出现旧会话的临时任务 / 工具上下文，但可以命中同一用户的长期记忆。

插件回归命令（在插件目录）：

```powershell
node --test tests/chat-sessions.test.cjs tests/chat-suggestions.test.cjs tests/mistake-book.test.cjs tests/markdown-fences.test.cjs tests/submission-observer.test.cjs tests/submission-background.test.cjs
node --check sidepanel.js
```

后端新增独立 smoke tests：`ChatSessionServiceSmokeTest`（34 项）、`ChatSessionDeleteRepositorySmokeTest`（16 项，MyBatis XML 和 Spring 事务代理）、`ChatSessionControllerSmokeTest`（9 项，MockMvc）。这些测试不调用真实模型或数据库；不替代真实 Edge、数据库和模型端到端联调。

普通消息保存回归：`mvn -pl gode-server-app -am test -Dtest=ChatMessageAppendTest -Dsurefire.failIfNoSpecifiedTests=false`，覆盖 HTTP 保存和历史恢复、相同内容直接追加、归属校验及保存失败，不调用真实模型或数据库。

# 对话下一步问题推荐

已实现 DTO、领域 Command/VO、API/领域接口、独立 ADK Runner、推荐 Service、Controller 和插件调用。
接口为 `POST /api/v1/chat/suggestions`，需要重新编译、启动包含新增 Controller 的后端。
不需要新增数据库表，不修改主对话、错题复盘、意图识别或 ReAct 节点。

## 文件和调用关系

- API 请求：`gode-server-api/.../dto/ChatSuggestionRequestDTO.java`，消息为 `List<MessageDTO>`。
- API 响应项：`gode-server-api/.../dto/ChatSuggestionDTO.java`，包含 `label`、`prompt`。
- API 契约：`com.gode.api.IChatSuggestionService.suggest(requestDTO)`，返回统一 `Response<List<ChatSuggestionDTO>>`。
- 领域输入：`com.gode.domain.agent.model.valobj.suggestion.ChatSuggestionCommand`，消息为 `List<Message>`。
- 领域输出：`ChatSuggestionVO`，不可变推荐项。
- 领域接口：`com.gode.domain.agent.service.IChatSuggestionService.suggest(command)`。
- 业务实现：`service/suggestion/ChatSuggestionService.java`。
- 模型接口：`service/suggestion/model/ChatSuggestionModel.java`。
- 模型实现：`service/suggestion/model/impl/AdkChatSuggestionModel.java`。
- HTTP 入口：`gode-server-trigger/.../http/ChatSuggestionController.java`。
- 插件请求及按钮生命周期：`gode-leetcode-sidepanel/sidepanel.js` 中的 `refreshSuggestions`。

Controller 将 DTO 转换成 Command，调用领域接口后再将 VO 转为 DTO。
API 和领域接口同名但属于不同包，Controller 同时使用时需要区分 import。
字段 `agentId`、`userId`、`sessionId`、`problemSlug`、`problemTitle` 均为必填。
本服务不按 sessionId 查询数据库，因此不能把客户端提供的 userId/sessionId 当作已完成会话授权校验。

## 输入与输出

请求结构：

```json
{
  "agentId": "demo-agent",
  "userId": "extension-xxx",
  "sessionId": "session-a",
  "problemSlug": "two-sum",
  "problemTitle": "两数之和",
  "messages": [
    { "role": "user", "content": "为什么不能先放进哈希表？" },
    { "role": "assistant", "content": "可能让当前元素与自己匹配。" }
  ]
}
```

输入最多 50 条，单条最多 60000 字符，合计最多 180000 字符；超限明确返回非法参数。
过滤掉 system、tool、未知角色、空内容及 null 消息，只将正常 user/assistant 文本发送给模型。
最近最多 6 条正常消息进入上下文，每条截取开头最多 3000 个 UTF-16 字符，总文本预算 12000 字符。
优先保留最新消息，保持时间正序，截断时不会截断 Unicode 代理对。
模型输入显式标记 `truncated` 和 `historyTruncated`，这些是字符预算，不是精确 token 预算。
用户身份及原会话 ID 不发送给模型，完整输入和输出不写入日志。

模型输出为 `{"suggestions":[{"label":"按钮文字","prompt":"完整问题"}]}`。
Service 校验字段类型及非空、按钮最多 12 个 Unicode 字符、问题最多 300 个 Unicode 字符。
兼容完整 JSON 围栏，但拒绝尾随内容、重复 JSON 键、超长输出和错误字段。
标签或问题重复时去重，最多返回 3 项；有效项不足 3 个时不编造补足。
模型故障、超时或错误 JSON 产生业务异常，插件保留兜底按钮，不影响主聊天。

HTTP 返回格式为：

```json
{
  "code": "0000",
  "info": "成功",
  "data": [
    { "label": "验证重复元素", "prompt": "用 [3,3] 验证先查询后插入的过程？" },
    { "label": "分析正确性", "prompt": "为什么先查询就能保证两个下标不同？" },
    { "label": "检查复杂度", "prompt": "这个方案的时间与空间复杂度分别是多少？" }
  ]
}
```

缺少参数返回业务码 `0002`；生成失败返回 `0001`，不会回传模型异常详情。
非法请求 JSON 由 Spring 返回 HTTP 400。接口不需要新增数据库表。

## 插件行为和过期请求保护

- 只有本轮收到 `done` 且有正常回答时，才在释放聊天锁后异步请求一次；不会逐字请求，也不会仅因打开历史会话而请求。
- 请求包含最近六条正常用户/助手消息，不包含欢迎语、工具事件、错误提示、Cookie 或 API Key。前端限制单条请求文本到 10000 字符，后端继续执行更严格的上下文预算。
- 按钮用纯文本渲染 `label`，点击将完整 `prompt` 填入输入框，用户可修改后发送；不自动执行推荐。
- 等待推荐期间聊天仍可用；生成回答期间建议按钮禁用。推荐默认最多等待 25 秒，超时、404、非法返回都安静保留原来的三个默认按钮。
- 新提问、停止生成、新会话、保存设置、切题/切标签页立即作废并取消旧推荐。请求版本号及题目、会话、Agent、用户、API 地址快照共同防止旧响应覆盖新按钮。
- 切题通知到来时先作废请求，再延迟读取题目；读取期间不能发送问题。旧题目读取、存储恢复、会话创建、聊天流和旧请求的 finally 也有隔离保护。
- 浏览器取消不保证后端或供应商立即停止计算，旧请求仍可能产生模型费用。

## 独立 Runner

只复用 `agentId` 对应 `AiAgentRegisterVO` 的 OpenAiApi 与模型名。
每次创建独立 LlmAgent、Runner、临时会话，使用内存 Session/Memory/Artifact Service。
不挂工具和插件，不改写父 Agent 或主聊天历史；使用 `StreamingMode.NONE`，只读取最终普通文本。
推荐不是已识别的用户真实意图，不自动发送推荐问题或执行操作。

无需配置也可运行。推荐模型默认等待 20 秒，可选配置范围为 1～60 秒：

```yaml
ai:
  agent:
    suggestion:
      timeout-seconds: 20
```

如果现有 YAML 已有 `ai.agent`，只在该节点下补充 `suggestion`，不要重复定义顶层键。
超时限制服务等待，不保证模型供应商立即停止已发出的请求。
Runner 订阅调度到独立 IO 线程，避免非流式同步模型调用阻塞订阅、导致 timeout 无法及时释放请求线程。
插件独立等待上限仍为 25 秒；如后端配置超过 25 秒，插件可能先回退默认建议。

## 验证范围

`ChatSuggestionServiceSmokeTest.main` 检查输入预算、数据隔离、JSON 校验、长度和去重。
`ChatSuggestionRunnerSmokeTest.main` 真正执行 ADK Runner，由 127.0.0.1 的临时模拟 HTTP 服务响应；不调用真实付费模型。
`ChatSuggestionSmokeTest.main` 顺序运行上述两组测试。
`ChatSuggestionControllerSmokeTest.main` 通过 MockMvc 验证真实 Controller + Service，模型采用本地替身；确认返回三个建议、请求映射、输入错误、模型错误、超时错误包装、路由和插件 CORS 预检。
Runner 测试还通过延迟的本地 HTTP 响应验证真实 1 秒超时，不调用付费模型。

插件检查：

```powershell
node --check sidepanel.js
node --test tests/chat-suggestions.test.cjs tests/mistake-book.test.cjs tests/submission-observer.test.cjs tests/submission-background.test.cjs
```

覆盖超时、取消、切题、连续提问、旧成功/失败/finally、新会话、设置变化及无 done 的流式结束；原有错题本和提交采集测试也继续运行。
测试不证明实际模型一定遵守“贴合当前对话”和“不剧透”，接入真实模型后仍需对代表性对话做质量检查。

本次检查时，运行中的 8091 服务新接口仍返回 404；全量 Maven 编译被原有七个装配节点缺少 `multiThread(...)` 实现阻塞。新增推荐文件已通过针对性编译和上述测试，但不代表运行中的后端已更新。

真实接口测试文件：`gode-server-app/src/test/resources/http/chat-suggestions.http`。重新启动后，可先在 IDEA HTTP Client 运行不调用模型的参数校验请求；再运行包含对话的请求，校验三个建议。第二步使用真实供应商，可能产生模型费用；模型有效输出不足三项时，该数量断言会提示质量问题，而不是自动填充伪造建议。

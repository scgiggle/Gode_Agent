# 结合学习记录的相似题推荐

升级原有 `recommendSimilarProblems` 工具，不新增插件页面、HTTP 接口或数据库表。工具名和模型可填写的参数保持不变：`currentProblemSlug`、可选 `count`（默认 3，范围 1～5）。插件仍直接展示 Agent 的 Markdown 回答。

## 链路

```text
父 Agent 请求推荐
→ toolRecommend 子 Agent（透传实际 userId，保留独立子会话）
→ SimilarProblemTool（ADK 注入 ToolContext，不让模型传 userId）
→ LearningProblemRecommendationService
   ├─ 力扣 GraphQL 查询最多 20 道真实相似候选
   ├─ 一次批量 GraphQL 补查各候选真实标签、难度
   ├─ 查询当前用户最近更新的最多 100 道错题
   ├─ 批量读取这些错题的最多 300 条近期复盘
   └─ 精确查询候选和当前题的错题状态
→ 过滤、排序，返回证据及推荐理由
→ Agent 使用中文解释推荐
```

没有新增分析用的 LLM 请求。规则统计并排序，原有推荐 Agent 负责组织回答。单子 Agent、批量子 Agent 和动态任务派发均改为透传实际用户，不再统一使用 `subagent-user`；子会话 ID 仍独立。

## 规则与证据

全部候选首先必须由力扣的相似题接口实际返回。去除当前题、重复题及非法 slug，并精确过滤用户标记“已掌握”的候选；不为了凑足数量重新加回这些题。

存在个人匹配或已掌握过滤时，按以下顺序排序：

1. 匹配未掌握错题知识点的权重，数组 / 字符串等通用标签权重较低；不同错题和复盘影响均封顶，防止单题多次保存压过其他题。
2. 候选题自身是否“待复习”或“复习中”。
3. 与当前题的难度差。
4. 力扣原始候选顺序作为稳定兜底。

中英文常见标签统一，例如 `hash-table` / `Hash Table` / `哈希表`。只使用候选自身真实标签匹配，不把当前题标签复制给所有候选，也不根据题名猜标签。

画像仅统计未掌握错题。按不同主记录统计 `problemCount`，按采样复盘统计 `reviewCount`，不会把同一道题的三次复盘当作三道错题。没有采样复盘时使用主表 `errorSummary` 的线索，不伪造复盘次数。

错误模式规则只读取 `failReason`，缺少复盘时读取主表摘要；不分析 `aiAnalysis` 或 `actionPlan`，避免将正确做法记成失败原因。规则识别下标 / 元素复用、边界、复杂度、状态转移、溢出、语法等线索，并对常见否定语句做过滤。这是启发式分类，并非 LLM 诊断，仍可能漏判或误判。

题目标签匹配后，`practiceFocus` 提供该知识点复盘中出现的建议检查点；不宣称候选题必然涉及这个边界。只有错题和复盘样本，没有完整通过记录，因此称为“疑似薄弱点”，不输出掌握率、失败率或能力百分比。

学习记录是用户级的持久数据，可以跨 Session 检索；不读取或合并其他 Session 的聊天历史、任务状态、工具缓存，也不写入新的长期记忆。

## 结果与降级

保留原有 `success`、`currentProblemSlug`、`count`、`recommendations`。新增：

- `personalized`：实际有薄弱标签匹配、待复习候选优先或已掌握过滤时为 true；仅有不相关学习记录不会假装个性化。
- `learningDataStatus`：`available`、`no_history`、`missing_user`、`unavailable`。
- `weakTopics`、`errorPatterns`：知识点 / 错误线索、不同错题数量、采样复盘数量、最多三个来源题目 slug。
- `sampledProblemCount`、`sampledReviewCount`、`excludedMasteredCount`、`message`。
- 每个候选新增 `topicTags`、`recommendationReason`、`matchedWeakTopics`、`practiceFocus`。

学习数据查询失败时丢弃半成品画像，保留普通相似题和原始顺序；不会因数据库异常让整个推荐工具不可用。身份缺失不读取共用伪用户。候选元数据补查失败时保留相似题，但不推测标签。相似题主查询失败仍返回工具失败，不编造结果。

本次修改没有新增表或修改表结构。Mapper 的所有读取都包含用户约束，复盘批量查询通过主表 JOIN 验证归属，空集合短路且 SQL 也限制为空结果。

## 使用与验证

更新并重启后端；插件不需要修改。打开题目并提问：

> 结合我错题本里的薄弱项，推荐三道与当前题相似的练习题，并说明推荐理由。

日志 `[LearningRecommendation] 推荐完成` 包含实际用户 ID、学习数据状态、采样数量、候选数量、过滤数量、是否个性化和最终返回数量。例如 `learningDataStatus=available personalized=true` 表示本次实际采用了个人匹配或掌握过滤。没有错题不应显示虚构的薄弱点。

新增测试 `LearningRecommendationSmokeTest`：真实 ADK FunctionTool 声明 / 注入、父子 Agent 身份及会话隔离、排序、状态变化、样本统计、降级、GraphQL 解析和 YAML 配置；不调用真实模型或数据库。

新增测试 `LearningRecommendationMapperSmokeTest`：真实 MyBatis XML 解析、归属 JOIN、批量参数绑定、空集合及查询上限。

另外只读验证了力扣公开 GraphQL 的 `two-sum` 标签查询和两个候选的批量标签查询，均返回 HTTP 200；不代表用户真实数据库与 Agent 对话已经完成端到端联调。

修改文件定向编译通过。完整 Maven 编译仍受原有七个装配节点缺失 `multiThread(...)` 阻碍，需解决框架兼容问题后再验证完整启动。本次未改动框架。

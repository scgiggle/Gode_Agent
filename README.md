# Gode · AI 力扣学习助手

Gode 将 AI 对话、代码检查、错题复盘和练习推荐放在力扣网页旁边，帮助你在做题过程中整理思路、定位错误并记录学习过程。

当前主要使用方式是 **Edge / Chrome 侧边栏插件 + Java 后端 + MySQL**。题目阅读、代码编辑、运行和提交在力扣网页中完成，插件负责读取上下文并与后端交互。

```text
力扣题目页面 → Gode 侧边栏 → gode-server → 模型 API / MCP 工具
                                  ↕
                                MySQL
```

## 功能介绍

| 功能             | 使用说明                                                                     |
| ---------------- | ---------------------------------------------------------------------------- |
| 当前题目识别     | 读取力扣中国站当前题目的标题、难度、标签和题目内容，切题后更新上下文         |
| AI 解题辅导      | 询问思路、复杂度、边界条件，支持多 Agent 分工及流式回答                      |
| 附带当前代码     | 勾选后，每次提问重新读取力扣编辑器中的代码，一起发送给 AI                    |
| 应用回答中的代码 | 点击代码块上的应用按钮，确认后替换力扣编辑器内容，可用 `Ctrl+Z` 撤销         |
| 多会话与历史记录 | 新建、切换、分页查看和删除聊天，消息保存在后端数据库                         |
| 下一步建议       | 回答完成后生成最多三个问题建议，点击填入输入框，自行确认发送                 |
| 错题本           | 添加当前题，记录错因、失败用例、分析及改进方案，支持搜索、筛选和复习状态管理 |
| 失败提交复盘     | 捕获实际失败提交的代码和判题结果，生成可编辑复盘草稿，确认后保存到错题本     |
| 复盘历史         | 同题多次保存会追加复盘，详情中可查看每次记录                                 |
| 相似题推荐       | 结合真实相似题候选、错题和复盘记录提供练习建议，并过滤已掌握的候选题         |
| 力扣个人笔记     | 配置登录凭据后，通过对话查询笔记；明确提出创建或修改要求时调用对应工具       |
| 本地文件读取     | 通过 Filesystem MCP 读取配置目录内的文件，供文件助手使用                     |


## 项目结构

以下列出 `.gitignore` 允许提交的主要文件与目录，省略编译产物、依赖缓存和本地配置。

```text
gode/
├─ .gitignore                # Git 忽略规则
├─ README.md                 # 项目介绍与使用说明
├─ LICENSE                   # MIT 许可证
├─ gode-leetcode-sidepanel/   # 浏览器侧边栏插件，当前主要使用入口
├─ gode-server/               # Java 后端，负责模型调用、工具编排和数据保存
│  ├─ gode-server-app/        # Spring Boot 启动入口、配置及测试
│  ├─ gode-server-api/        # 接口定义与 DTO
│  ├─ gode-server-trigger/    # HTTP 接口等入口
│  ├─ gode-server-domain/     # Agent 编排和业务逻辑
│  ├─ gode-server-infrastructure/ # 数据库等基础设施实现
│  ├─ gode-server-types/      # 公共类型与异常
│  └─ gode-server-case/       # 场景相关模块
├─ gode-wrench/               # 设计框架公共依赖，后端构建前先安装
└─ docs/                     # 数据库脚本与功能说明
```

浏览器扩展加载目录为 `gode-leetcode-sidepanel`，可直接加载，无需前端构建。

## 所用技术

| 层次         | 技术                                                                    |
| ------------ | ----------------------------------------------------------------------- |
| 侧边栏前端   | HTML、CSS、原生 JavaScript、Manifest V3、Side Panel API、Chrome Storage |
| 后端         | Java 17、Spring Boot 3.4.3、Maven                                       |
| Agent 与模型 | Google ADK 1.2.0、Spring AI 1.1.5、OpenAI 兼容 Chat Completions API     |
| 工具调用     | MCP、标准输入输出传输、Node.js / npx、力扣与 Filesystem MCP Server      |
| 数据存储     | MySQL 8、MyBatis 3.0.4、Spring 事务                                     |
| 流式通信     | HTTP Fetch、NDJSON                                                      |

## 本地启动

以下步骤以 Windows + IDEA + Edge 为例，命令从本 README 所在的 `gode` 目录执行。

### 1. 准备环境

- JDK 17，确认 IDEA 项目 SDK、Maven 使用的 JDK 和终端 `JAVA_HOME` 一致。
- Maven 3.9.x。
- MySQL 8.x。
- Node.js，建议使用 22 LTS 的较新版本，并确保 `node`、`npm`、`npx` 在 PATH 中。
- 支持侧边栏扩展的新版 Edge 或 Chrome。
- 可用的模型 API 地址、API Key 和模型名称。

### 2. 初始化数据库

首次使用时，新建空数据库 `gode`，然后执行 [首次建表脚本](docs/mysql/gode-init.sql)。


### 3. 配置后端

主要配置文件：[application-dev.yml](gode-server/gode-server-app/src/main/resources/application-dev.yml)。

先检查数据库连接地址、用户名和密码。当前开发配置默认连接本机 `3306` 端口的 `gode` 数据库，服务默认运行在 `8091` 端口。

模型相关配置支持以下环境变量：

| 变量                          | 用途                                                 | 是否需要                         |
| ----------------------------- | ---------------------------------------------------- | -------------------------------- |
| `DB_PASSWORD`                 | 数据库密码                                           | 按本机数据库填写                 |
| `AI_BASE_URL`                 | 模型服务基础地址                                     | 使用其他模型服务或中转地址时填写 |
| `AI_API_KEY`                  | 模型调用密钥                                         | 需要                             |
| `AI_MODEL`                    | 服务商实际支持的模型名称                             | 按使用的模型填写                 |
| `GODE_PERSONAL_NOTES_ENABLED` | 是否允许在插件设置中配置个人笔记，值为 `true` 时开启 | 使用个人笔记设置时需要           |
| `LEETCODE_SESSION`            | 后端启动时使用的力扣登录凭据                         | 可选，也可启动后在插件中填写     |

在 IDEA 的 `Application` 运行配置中，通过 **Environment variables / 环境变量** 填写。也可以在 PowerShell 中设置后，从同一个窗口启动后端：

```powershell
$env:DB_PASSWORD = '你的数据库密码'
$env:AI_BASE_URL = 'https://api.openai.com'
$env:AI_API_KEY = '你的模型 API Key'
$env:AI_MODEL = 'gpt-4o-mini'
# 使用插件中的力扣笔记配置时再启用：
$env:GODE_PERSONAL_NOTES_ENABLED = 'true'
```

上述模型名称仅为示例，请以服务商支持的模型为准。

开发配置中的 Filesystem MCP 默认目录为 `D:\mcp-sandbox`。请先创建该目录，或把对应 MCP 参数改为你允许助手读取的实际目录。

### 4. 构建并启动后端

先在 `gode-wrench` 目录执行 `mvn clean install -DskipTests`，将公共依赖打包为 JAR 并安装到本地 Maven 仓库，再运行 `gode-server` 的启动类 `com.gode.Application`。

### 5. 加载侧边栏插件

1. Edge 打开 `edge://extensions/`，Chrome 打开 `chrome://extensions/`。
2. 开启“开发人员模式”，点击“加载解压缩的扩展”。
3. 选择本项目的 `gode-leetcode-sidepanel` 文件夹。
4. 点击插件图标打开侧边栏。
5. 打开 `https://leetcode.cn/problems/...` 题目页面，刷新一次页面。
6. 在齿轮设置中检查：后端地址为 `http://localhost:8091/api/v1`，默认 Agent ID 为 `demo-agent`。
7. 如需使用力扣个人笔记，先在力扣中国站登录，按 `F12` 打开开发者工具，进入 **Application（应用程序）→ Storage（存储）→ Cookie → https://leetcode.cn**，找到名称为 `LEETCODE_SESSION` 的记录，复制其 **Value（值）**。
8. 回到插件齿轮设置，先保存后端地址和 Agent ID，再将复制的值粘贴到插件设置下的 **LEETCODE_SESSION Cookie** 输入框，点击“保存并验证”。只需填写 Cookie 的值，无需添加 `LEETCODE_SESSION=` 前缀。



## 温馨提示

- **先确认题目，再发送问题。** 页面切题后等待标题更新。
- **模型回答需要结合实际运行检查。** 应用代码前先阅读修改内容，运行和提交仍由你在力扣页面操作。
- **保留插件存储。** 当前用户 ID 由插件生成并保存在本地；清空存储或重装后可能生成新 ID，旧聊天和错题不会自动关联到新 ID。
- **删除前确认。** 删除聊天不会删除错题或长期记忆；删除错题会同时删除该题复盘，相关聊天保留。

## 许可证

本项目采用 [MIT](LICENSE) 许可证，允许使用、修改、分发和商用，复制或分发时须保留版权声明及许可声明。软件按现状提供，不提供担保，具体条款以许可证全文为准。第三方依赖遵循各自的许可证。

## 学习项目说明

本项目为学习项目，主要用于探索 AI Agent、浏览器插件开发与算法学习辅助。

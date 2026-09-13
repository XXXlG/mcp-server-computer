# mcp-server-computer

> 一个基于 Spring Boot + Spring AI 的 MCP (Model Context Protocol) Server，为 AI 客户端（Cherry Studio / Claude Desktop / Trae 等）提供**电脑信息查询**和**本地文件系统操作**能力。

---

## 一、技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 17+ | 运行时 |
| Spring Boot | 3.x | 应用框架 |
| Spring AI | 1.0.0-M6 | MCP Server 实现 |
| Lombok | - | 简化代码 |

## 二、项目结构

```
mcp-server-computer/
├── pom.xml
├── README.md
└── src/main/
    ├── java/top/xxliang/mcpserver/computer/
    │   ├── McpServerComputerApplication.java      ← 启动类
    │   ├── config/
    │   │   ├── McpToolConfig.java                 ← Tool 注册中心
    │   │   └── StartupInfoListener.java           ← （废弃）启动打印
    │   └── domain/
    │       ├── model/                             ← （废弃）原有 DTO，已被 Map 替代
    │       └── server/
    │           ├── ComputerService.java           ← 电脑信息查询（1 个 Tool）
    │           └── FileSystemService.java         ← 文件系统操作（4 个 Tool）
    └── resources/
        └── application.yml                        ← 应用 + MCP 配置
```

---

## 三、核心类详解

### 3.1 `McpServerComputerApplication`

| 项 | 说明 |
|----|------|
| 职责 | Spring Boot 启动入口 |
| 注解 | `@SpringBootApplication` |
| 是否包含 Tool | ❌ |

```
启动时自动触发：
  → Spring 扫描 @Service / @Configuration
  → McpToolConfig 注册所有 @Tool 方法
  → Spring AI MCP Server Starter 启动 stdio transport
  → 等待 AI 客户端拉起并通过 JSON-RPC 通信
```

---

### 3.2 `config/McpToolConfig` — Tool 注册中心

| 项 | 说明 |
|----|------|
| 职责 | 把所有 Service 中的 `@Tool` 方法注册为 MCP 可调用工具 |
| 注解 | `@Configuration` |
| 是否包含 Tool | ❌（只负责注册） |

**关键代码：**

```java
@Bean
public ToolCallbackProvider mcpTools(
        ComputerService computerService,
        FileSystemService fileSystemService) {
    return MethodToolCallbackProvider.builder()
            .toolObjects(computerService, fileSystemService)   // 传 Service 对象，自动扫描 @Tool
            .build();
}
```

**扩展方式：** 新增 Service（如 `NetworkService`）后，只需在参数中追加 `.toolObjects(computerService, fileSystemService, networkService)` 即可。

---

### 3.3 `domain/server/ComputerService` — 电脑信息查询

| 项 | 说明 |
|----|------|
| 职责 | 获取当前运行机器的操作系统、Java 版本、硬件信息 |
| 注解 | `@Service` / `@Slf4j` |
| Tool 数量 | **1 个** |
| 返回类型 | `Map<String, Object>`（灵活 Map，无固定 DTO 绑定） |
| 安全策略 | 无（纯系统属性读取） |

#### Tool 列表

##### 🛠 `queryConfig`

| 项 | 说明 |
|----|------|
| 描述 | 获取当前机器的操作系统、Java 版本、用户目录等配置信息 |
| 权限 | ✅ 无安全限制，可在任意环境调用 |
| 返回 | 包含 osName / osVersion / osArch / userName / userHome / userDir / javaVersion / javaVendor 的 Map |

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `computer` | `String` | 是 | 电脑名称标识，任意值，用于区分不同查询 |

**附加逻辑：** 根据 `os.name` 自动补充硬件信息（跨平台）：

| 平台 | 硬件信息命令 |
|------|------------|
| macOS | `system_profiler SPHardwareDataType` |
| Windows | `systeminfo` |
| Linux | `lshw -short` |

**安全信息：**
- ⚠️ 使用 `Runtime.exec()` 执行系统命令，命令来自**代码硬编码**，不接受用户输入拼接，无命令注入风险
- 硬件信息获取失败时静默降级（warn 日志 + 不返回 hardwareInfo 字段）

---

### 3.4 `domain/server/FileSystemService` — 文件系统操作

| 项 | 说明 |
|----|------|
| 职责 | 文件和目录的读写操作 |
| 注解 | `@Service` / `@Slf4j` |
| Tool 数量 | **4 个** |
| 安全策略 | 读写**分离**（见下表） |

#### 权限策略

| 操作类型 | Tool | 安全限制 | 相对路径基准 |
|---------|------|---------|------------|
| 📖 只读 | `listDirectory` / `readFile` | **无限制**，可访问任意路径 | `user.dir`（当前工作目录） |
| ✏️ 写操作 | `createFile` / `appendFile` | **受 `mcp.file.root` 限制**，只能写入安全根目录 | `rootDir`（安全根目录） |

#### 路径解析方法

| 方法 | 使用者 | 逻辑 |
|------|--------|------|
| `resolvePathForRead()` | listDirectory / readFile | 只做相对→绝对路径转换，不校验 |
| `resolvePathForWrite()` | createFile / appendFile | 相对路径基于 rootDir，越界抛 SecurityException |

#### Tool 列表

##### 🛠 `listDirectory`

| 项 | 说明 |
|----|------|
| 描述 | 列出指定目录下的文件和子目录，返回每项的类型、大小和修改时间 |
| 权限 | 📖 只读，**无安全限制** |
| 返回 | 格式化文本（含 📁 目录 / 📂 子目录 / 📄 文件 图标） |

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `path` | `String` | 是 | 目录路径，支持绝对路径（`/Users/xxliang`）或相对路径（`./src`） |
| `recursive` | `boolean` | 是 | 是否递归显示子目录，默认 `false` |

---

##### 🛠 `readFile`

| 项 | 说明 |
|----|------|
| 描述 | 读取指定文本文件的全部内容 |
| 权限 | 📖 只读，**无安全限制** |
| 返回 | 文件原始文本内容 |

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `filePath` | `String` | 是 | 文件路径，支持绝对路径或相对路径 |
| `maxChars` | `int` | 是 | 最大读取字符数，超过则截断并提示。默认 `50000` |

**二进制文件拦截：** 自动跳过 `.png` / `.jpg` / `.zip` / `.jar` / `.class` / `.exe` 等扩展名。

---

##### 🛠 `createFile`

| 项 | 说明 |
|----|------|
| 描述 | 创建新文件或覆盖已有文件（自动创建父目录） |
| 权限 | ✏️ 写操作，**受 `mcp.file.root` 安全限制** |
| 返回 | 操作结果文本（✅ 新建 / ✅ 覆盖更新 + 文件路径 + 字符数） |

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `filePath` | `String` | 是 | 目标文件路径，相对路径基于**安全根目录**解析 |
| `content` | `String` | 是 | 要写入的完整文件内容 |

---

##### 🛠 `appendFile`

| 项 | 说明 |
|----|------|
| 描述 | 向已有文件末尾追加内容（文件不存在则自动创建） |
| 权限 | ✏️ 写操作，**受 `mcp.file.root` 安全限制** |
| 返回 | 操作结果文本（✅ 已追加 + 文件路径 + 追加字符数） |

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `filePath` | `String` | 是 | 目标文件路径，相对路径基于**安全根目录**解析 |
| `content` | `String` | 是 | 要追加的内容 |
| `prependNewline` | `boolean` | 是 | 追加前是否先加换行符（文件已存在时生效），默认 `true` |

#### 安全信息

```
┌─────────────────────────────────────────────────┐
│  写操作安全根目录 (mcp.file.root)                 │
│  默认值: ${user.home}/Documents/mcp-workspace    │
│                                                   │
│  ┌─────────────┐  ✅ 允许写入                     │
│  │  rootDir/   │                                  │
│  │  ├── a.txt  │                                  │
│  │  └── sub/   │                                  │
│  │      └── b.txt                                 │
│  └─────────────┘                                  │
│                                                   │
│  写入 /etc/passwd           →  ⛔ SecurityException│
│  写入 ~/.ssh/authorized_keys →  ⛔ SecurityException│
│  写入 ../../outside.txt      →  ⛔ SecurityException│
└─────────────────────────────────────────────────┘
```

**安全根目录配置优先级（从高到低）：**

| 优先级 | 来源 | 示例 |
|--------|------|------|
| 1 | JVM -D 参数 | `-Dmcp.file.root=/Users/xxliang/Desktop/daily-report` |
| 2 | 环境变量 | `MCP_FILE_ROOT=/data/reports` |
| 3 | application.yml | `mcp.file.root: ${user.home}/Documents/mcp-workspace` |
| 4 | @Value SpEL 默认值 | `systemProperties['user.home'] + '/Documents/mcp-workspace'` |

**不配置时的行为：** 默认值 `~/Documents/mcp-workspace` 自动创建（`Files.createDirectories`）。

---

### 3.5 `config/StartupInfoListener` — （废弃）启动监听

| 项 | 说明 |
|----|------|
| 职责 | 原本用于在 Web 模式下打印访问地址 |
| 状态 | **`@Deprecated` + `@Component` 已注释**，不生效 |
| 原因 | 当前使用 `stdio` 模式（`web-application-type: none`），无 HTTP 端口可展示 |

---

## 四、配置说明

### 4.1 application.yml

```yaml
spring:
  application:
    name: mcp-server-computer
  ai:
    mcp:
      server:
        name: ${spring.application.name}
        version: 1.0.0
  main:
    banner-mode: off
    web-application-type: none          # stdio 模式 = 无 Web

logging:
  file:
    name: ${spring.application.name}.log

# 自定义配置
mcp:
  file:
    root: ${user.home}/Documents/mcp-workspace   # 写操作安全根目录
```

### 4.2 启动必需参数

```bash
# stdio 模式启动（AI 客户端通过子进程 stdin/stdout 通信）
java -Dspring.ai.mcp.server.stdio=true \
     -Dmcp.file.root=/Users/xxliang/Desktop/daily-report \
     -jar mcp-server-computer.jar
```

| 参数 | 必填 | 说明 |
|------|------|------|
| `-Dspring.ai.mcp.server.stdio=true` | ✅ | 启用 stdio transport（不是 yml 配置，必须 JVM 参数） |
| `-Dmcp.file.root=...` | ❌ | 覆盖写操作安全根目录 |

---

## 五、客户端集成

### Cherry Studio / Claude Desktop 配置示例

```json
{
  "mcpServers": {
    "mcp-server-computer": {
      "command": "java",
      "args": [
        "-Dspring.ai.mcp.server.stdio=true",
        "-Dmcp.file.root=/Users/xxliang/Desktop/daily-report",
        "-jar",
        "/path/to/mcp-server-computer.jar"
      ]
    }
  }
}
```

AI 客户端连接后会自动：
1. `initialize` 握手
2. `tools/list` 获取 5 个 Tool 定义（含 name / description / inputSchema）
3. 根据用户意图推理并调用合适的 Tool

---

## 六、全部 Tool 速查表

| # | Tool 名称 | 所属类 | 权限 | 返回类型 |
|---|----------|--------|------|---------|
| 1 | `queryConfig` | ComputerService | 无限制 | Map |
| 2 | `listDirectory` | FileSystemService | 📖 只读无限制 | String |
| 3 | `readFile` | FileSystemService | 📖 只读无限制 | String |
| 4 | `createFile` | FileSystemService | ✏️ 受 rootDir 限制 | String |
| 5 | `appendFile` | FileSystemService | ✏️ 受 rootDir 限制 | String |

---

## 七、扩展指南

### 新增 Tool 的 3 步：

**Step 1** — 在 Service 中加方法 + `@Tool` / `@ToolParam` 注解：

```java
@Service
public class NetworkService {

    @Tool(description = "发送 HTTP GET 请求并返回响应内容")
    public String httpGet(
            @ToolParam(description = "目标 URL") String url,
            @ToolParam(description = "请求超时秒数") int timeout) { ... }
}
```

**Step 2** — `McpToolConfig` 中注册新 Service：

```java
return MethodToolCallbackProvider.builder()
        .toolObjects(computerService, fileSystemService, networkService)   // ← 追加
        .build();
```

**Step 3** — 重启，AI 客户端 `tools/list` 自动发现新 Tool。

### 建议扩展方向

| 方向 | Service 名 | 可能的 Tool |
|------|-----------|------------|
| 进程执行 | `ProcessService` | `runCommand`（⚠️ 需白名单）、`listProcesses` |
| 网络 | `NetworkService` | `httpGet`、`httpPost` |
| 文件增强 | `FileSystemService` | `deleteFile`、`findFiles`（模式匹配）、`copyFile` |
| 剪贴板 | `ClipboardService` | `readClipboard`、`writeClipboard` |

---

## 八、废弃代码说明

| 路径 | 状态 | 说明 |
|------|------|------|
| `domain/model/ComputerFunctionRequest.java` | 废弃 | 原设计的请求 DTO，因 `@Tool` 要求参数拆成 `@ToolParam` 而不再使用 |
| `domain/model/ComputerFunctionResponse.java` | 废弃 | 原设计的响应 DTO，已替换为灵活的 `Map<String, Object>` |
| `config/StartupInfoListener.java` | 废弃 | `@Deprecated` + `@Component` 注释，stdio 模式下无意义 |

> 💡 以上废弃文件可安全删除，不影响任何功能。

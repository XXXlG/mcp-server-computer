# MCP Server 开发指南

> 基于 Spring AI MCP Server Starter，从零实现一个可被 AI 客户端调用的工具服务。
> 
> 本文档以 `mcp-server-computer`（查询电脑配置）为示例，讲解完整开发流程，后端同学可以照着复现自己的 MCP Server。

---

## 一、项目概述

MCP（Model Context Protocol）是 Anthropic 推出的开放协议，用于让 AI 客户端（Claude Desktop、Cherry Studio、Trae 等）连接外部工具和数据源。

**本项目**实现了一个 MCP Server，对外暴露一个 `queryConfig` 工具，AI 客户端调用后可以拿到服务端机器的操作系统、Java 版本、用户目录等信息。

```
AI 客户端 (Claude Desktop / Cherry Studio / Trae)
        │  stdio / SSE
        ▼
   MCP Server (本项目)
        │  @Tool 注解方法
        ▼
   业务逻辑 (ComputerService)
```

### 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 必须，Spring AI 要求 |
| Spring Boot | 3.4.3 | 基础框架 |
| Spring AI | 1.0.0-M6 | 提供 MCP Server 能力 |
| Lombok | - | 简化代码 |

---

## 二、环境准备

1. **JDK 17+**（Spring Boot 3.x 最低要求）
2. **Maven 3.8+**
3. **IDE**（IntelliJ IDEA 推荐）

---

## 三、创建项目与依赖

### 3.1 用 Spring Initializr 创建项目

访问 https://start.spring.io/ ，选好 Java 17 + Spring Boot 3.4.x，生成项目导入 IDE。

### 3.2 pom.xml 完整示例（直接复制可用）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>top.xxliang.mcpserver</groupId>
    <artifactId>mcp-server-computer</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <spring-ai.version>1.0.0-M6</spring-ai.version>
    </properties>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.3</version>
    </parent>

    <!-- Spring AI Milestone 仓库：1.0.0-M6 是里程碑版，必须配置，否则依赖下载失败 -->
    <repositories>
        <repository>
            <id>spring-milestones</id>
            <url>https://repo.spring.io/milestone</url>
            <snapshots><enabled>false</enabled></snapshots>
        </repository>
    </repositories>

    <dependencies>
        <!-- 关键依赖：MCP Server Starter，自动装配 MCP 协议 -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-mcp-server-spring-boot-starter</artifactId>
        </dependency>

        <!-- 可选：Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- 测试 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <!-- Spring AI 的 BOM，统一管理版本 -->
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
            <!-- 打包成可执行 jar -->
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

> **关于 fastjson**：生产代码用 Spring AI 自带的 **Jackson** 做序列化，不需要额外引 fastjson。示例项目中 fastjson 仅在 `ComputerTest.java` 测试类中使用，属于测试依赖（实际也可以删掉）。
>
> **为什么没有 `spring-boot-starter-web`？**
> MCP Server 默认走 **stdio** 模式（通过标准输入输出通信），不需要 Web 容器。如果需要 SSE/HTTP 模式才需要加 Web 依赖，见扩展章节。

---

## 四、核心配置

### 4.1 application.yml

```yaml
spring:
  application:
    name: mcp-server-computer

  ai:
    mcp:
      server:
        name: ${spring.application.name}    # MCP 协议中的 server name
        version: 1.0.0                       # MCP 协议中的 server version

  # 关键：禁用 Web 容器，MCP stdio 模式不需要 HTTP 端口
  main:
    web-application-type: none
    banner-mode: off

logging:
  file:
    name: ${spring.application.name}.log
```

### 4.2 关于 stdio 模式的启动参数（重要！）

Spring AI MCP Server 默认**不自动启用** stdio 传输。需要在启动时加 JVM 参数：

```bash
java -Dspring.ai.mcp.server.stdio=true -jar target/mcp-server-computer-1.0-SNAPSHOT.jar
```

> **为什么不写到 yml 里？** 这个属性通常由客户端在拉起 MCP Server 进程时通过命令行注入（Claude Desktop、Cherry Studio 都是这么做的）。开发调试时你可以写到 yml 里方便本地跑，但发布时建议移除。

---

## 五、编写 Tool 方法

### 5.1 核心注解说明

| 注解 | 作用 |
|------|------|
| `@Tool(description = "...")` | 标记方法为 MCP 工具，description 会告诉 AI 这个工具是干嘛的 |
| `@ToolParam(description = "...")` | 标记方法参数，description 帮助 AI 理解每个参数的含义 |

### 5.2 写法示例（本项目的 ComputerService）

```java
package top.xxliang.mcpserver.computer.domain.server;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ComputerService {

    /**
     * 查询电脑配置信息
     *
     * @param computer 电脑名称（AI 会根据 description 自动理解这个参数的含义）
     * @return 电脑配置信息
     */
    @Tool(description = "获取电脑配置")
    public ComputerFunctionResponse queryConfig(
            @ToolParam(description = "电脑名称") String computer) {

        log.info("获取电脑配置信息: {}", computer);

        // 业务逻辑：获取系统属性
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String javaVersion = System.getProperty("java.version");
        // ...

        ComputerFunctionResponse response = new ComputerFunctionResponse();
        response.setOsName(osName);
        response.setOsVersion(osVersion);
        response.setJavaVersion(javaVersion);
        // ...

        return response;
    }
}
```

### 5.3 ⚠️ 参数定义的正确姿势

**Spring AI 的 `@Tool` 方法参数必须是基础类型**（String、int、boolean 等）或简单包装类型，每个参数单独加 `@ToolParam`。

> ❌ **错误做法**（本项目早期尝试过的方式）：
> ```java
> @Tool(description = "获取电脑配置")
> public ComputerFunctionResponse queryConfig(ComputerFunctionRequest request) { ... }
> ```
> 把参数包装成一个 Request 对象，Spring AI 不会自动解析对象字段为独立的 tool 参数，AI 客户端拿不到正确的参数 schema。

**结论**：`ComputerFunctionRequest` 这个类在当前实现中是**未使用**的废弃代码，可以删除。如果确实需要复杂入参，可以把对象字段拆成独立的 `@ToolParam`。

### 5.4 响应对象

响应对象也可以用 Lombok + Jackson 注解增强：

```java
package top.xxliang.mcpserver.computer.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

@Data
public class ComputerFunctionResponse {

    @JsonProperty(required = true, value = "osName")
    @JsonPropertyDescription("操作系统名称")
    private String osName;

    @JsonProperty(required = true, value = "javaVersion")
    @JsonPropertyDescription("Java 运行时版本")
    private String javaVersion;

    // ... 其他字段
}
```

`@JsonPropertyDescription` 让 AI 客户端更好地理解返回结构。

---

## 六、注册 Tool 到 MCP Server

光写好 `@Tool` 方法还不够，Spring AI 需要通过一个 `ToolCallbackProvider` Bean 把你的 Service 注册进去。

### 6.1 McpToolConfig

```java
package top.xxliang.mcpserver.computer.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.xxliang.mcpserver.computer.domain.server.ComputerService;

@Configuration
public class McpToolConfig {

    /**
     * 将 ComputerService 中所有带 @Tool 注解的方法注册为 MCP 工具
     */
    @Bean
    public ToolCallbackProvider computerTools(ComputerService computerService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(computerService)    // 可以传多个 service
                .build();
    }
}
```

> **原理**：`MethodToolCallbackProvider` 会扫描传入对象的所有方法，把带 `@Tool` 注解的方法解析成 MCP 协议里的 `tools/list` 响应项。

---

## 七、启动类

标准的 Spring Boot 启动类，没什么特别：

```java
package top.xxliang.mcpserver.computer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class McpServerComputerApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpServerComputerApplication.class);
    }
}
```

---

## 八、打包与运行

### 8.1 打包

```bash
mvn clean package -DskipTests
```

产物：`target/mcp-server-computer-1.0-SNAPSHOT.jar`

### 8.2 本地调试运行

加上 stdio 开关启动：

```bash
java -Dspring.ai.mcp.server.stdio=true -jar target/mcp-server-computer-1.0-SNAPSHOT.jar
```

如果启动看到日志里有 MCP 相关的初始化信息，说明成功了。由于是 stdio 模式，进程会一直挂着等待客户端连接，**不要 Ctrl+C 关掉**。

### 8.3 快速验证

可以先不接客户端，直接运行单元测试/集成测试，见下一节。

---

## 九、测试验证

### 9.1 单元测试：验证 Tool 注册

```java
@SpringBootTest
class McpToolRegistrationTest {

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Test
    void toolCallbackProvider_shouldExist() {
        assertNotNull(toolCallbackProvider, "ToolCallbackProvider bean 必须存在");
    }

    @Test
    void shouldContainQueryConfigTool() {
        FunctionCallback[] callbacks = toolCallbackProvider.getToolCallbacks();
        assertTrue(callbacks.length >= 1, "至少应注册 1 个 tool");

        boolean found = Arrays.stream(callbacks)
                .anyMatch(c -> "queryConfig".equals(c.getName()));
        assertTrue(found, "应包含 queryConfig tool");
    }
}
```

### 9.2 集成测试：stdio 端到端

用 MCP Java SDK 直接拉起 jar 进程，通过 stdio 做 JSON-RPC 通信，验证 `initialize → tools/list → tools/call` 完整链路。

```java
class McpStdioIntegrationTest {

    // ⚠️ 改成你自己 jar 的绝对路径
    private static final String SERVER_JAR =
        "/你的路径/mcp-server-computer/target/mcp-server-computer-1.0-SNAPSHOT.jar";

    private McpSyncClient client;

    @BeforeEach
    void setUp() {
        ServerParameters params = ServerParameters.builder("java")
                .args("-Dspring.ai.mcp.server.stdio=true", "-jar", SERVER_JAR)
                .build();

        client = McpClient.sync(new StdioClientTransport(params))
                .requestTimeout(Duration.ofSeconds(30))
                .clientInfo(new McpSchema.Implementation("test-client", "1.0.0"))
                .build();

        client.initialize();   // 发起握手
    }

    @Test
    void toolsList_shouldReturnQueryConfig() {
        McpSchema.ListToolsResult result = client.listTools();

        boolean found = result.tools().stream()
                .anyMatch(t -> "queryConfig".equals(t.name()));
        assertTrue(found, "应包含 queryConfig");
    }

    @Test
    void toolsCall_queryConfig_shouldReturnOsInfo() {
        McpSchema.CallToolResult result = client.callTool(
            new McpSchema.CallToolRequest("queryConfig", Map.of("computer", "xxliang"))
        );

        String text = result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .findFirst().orElse("");

        assertTrue(text.contains("osName"));
        assertTrue(text.contains("javaVersion"));
    }
}
```

### 9.3 运行测试

```bash
mvn test
```

---

## 十、MCP 客户端对接

写好的 MCP Server 怎么让 AI 客户端调起来？下面以几个主流客户端为例。

### 10.1 Cherry Studio（推荐，简单好用）

编辑 Cherry Studio 的 MCP 配置（JSON 格式）：

```json
{
  "mcpServers": {
    "mcp-server-computer": {
      "command": "java",
      "args": [
        "-Dspring.ai.mcp.server.stdio=true",
        "-jar",
        "/你的绝对路径/mcp-server-computer/target/mcp-server-computer-1.0-SNAPSHOT.jar"
      ]
    }
  }
}
```

保存后 Cherry Studio 会自动拉起这个进程。在聊天窗口输入 `@mcp-server-computer`，就能看到你的 `queryConfig` 工具了。

### 10.2 Claude Desktop

配置文件路径（macOS）：
```
~/Library/Application Support/Claude/claude_desktop_config.json
```

```json
{
  "mcpServers": {
    "mcp-server-computer": {
      "command": "java",
      "args": [
        "-Dspring.ai.mcp.server.stdio=true",
        "-jar",
        "/你的绝对路径/mcp-server-computer/target/mcp-server-computer-1.0-SNAPSHOT.jar"
      ]
    }
  }
}
```

重启 Claude Desktop 后生效。

### 10.3 Trae

Trae 内置 MCP 支持，在设置 → MCP 中添加：

| 字段 | 值 |
|------|-----|
| 名称 | mcp-server-computer |
| 类型 | command |
| Command | `java` |
| Args | `-Dspring.ai.mcp.server.stdio=true -jar /你的路径/target/mcp-server-computer-1.0-SNAPSHOT.jar` |

---

## 十一、扩展：切换到 SSE / HTTP 模式

默认 stdio 模式是客户端拉起 MCP Server 子进程。如果希望 MCP Server 独立运行（类似 HTTP API），可以切换到 SSE 模式。

### 11.1 增加 Web 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

### 11.2 修改 application.yml

```yaml
spring:
  main:
    web-application-type: servlet    # 恢复 Web 容器

server:
  port: 8080

spring:
  ai:
    mcp:
      server:
        name: ${spring.application.name}
        version: 1.0.0
        # SSE 模式下不需要 stdio 开关
```

### 11.3 客户端对接（SSE 模式）

以 Cherry Studio 为例，args 换成 URL：

```json
{
  "mcpServers": {
    "mcp-server-computer": {
      "url": "http://localhost:8080/sse"
    }
  }
}
```

### 11.4 stdio vs SSE 对比

| 维度 | stdio | SSE / HTTP |
|------|-------|------------|
| 启动方式 | 客户端拉起子进程 | 独立运行，客户端连接 URL |
| 部署 | 随客户端进程生命周期 | 可独立部署、多客户端共享 |
| 适合场景 | 本地开发、单机使用 | 服务端部署、团队共享 |
| 依赖 | 无 Web 容器 | 需要 Web 依赖 |

---

## 十二、项目结构总结

```
mcp-server-computer/
├── pom.xml
└── src/main/java/top/xxliang/mcpserver/computer/
    ├── McpServerComputerApplication.java   # 启动类
    ├── config/
    │   └── McpToolConfig.java               # 注册 ToolCallbackProvider ⭐核心配置
    └── domain/
        ├── model/
        │   ├── ComputerFunctionRequest.java  # ⚠️ 废弃代码，未被使用，可删除
        │   └── ComputerFunctionResponse.java
        └── server/
            └── ComputerService.java         # @Tool 方法所在 ⭐核心业务
```

**三个必须写的文件**：
1. `ComputerService` — 用 `@Tool` + `@ToolParam` 定义工具方法
2. `McpToolConfig` — 把 Service 注册成 `ToolCallbackProvider`
3. `application.yml` — 配置 MCP Server 的 name/version

---

## 十三、踩坑总结

| # | 坑 | 解决方案 |
|---|----|---------|
| 1 | 启动后客户端连不上，握手报缺少 tools capability | 检查 `McpToolConfig` 是否正确注册了 `ToolCallbackProvider` Bean |
| 2 | stdio 模式启动了但没有 stdio 输出 | 启动参数必须加 `-Dspring.ai.mcp.server.stdio=true`，yml 里写不生效 |
| 3 | `@Tool` 方法入参写成自定义 Request 对象 | 必须拆成基础类型参数 + `@ToolParam` 注解 |
| 4 | Milestone 版本依赖下载失败 | pom.xml 里加 Spring Milestone 仓库（`https://repo.spring.io/milestone`） |
| 5 | 打包后运行 `ClassNotFoundException` | 用 `spring-boot-maven-plugin` 打包，不要用普通 `maven-jar-plugin` |
| 6 | 客户端配置后启动报错找不到 jar | 检查 jar 路径是否是**绝对路径**，相对路径容易出错 |

---

## 十四、快速 Checklist

从零复现一个 MCP Server，按这个顺序做：

- [ ] 1. 创建 Spring Boot 3.4.x 项目（Java 17）
- [ ] 2. pom.xml 引入 `spring-ai-mcp-server-spring-boot-starter` + BOM + Milestone 仓库
- [ ] 3. application.yml 配好 `spring.ai.mcp.server.name/version` 和 `web-application-type: none`
- [ ] 4. 写一个 `@Service` 类，方法加 `@Tool` + `@ToolParam`
- [ ] 5. 写 `@Configuration` 类，暴露 `ToolCallbackProvider` Bean
- [ ] 6. `mvn clean package` 打包
- [ ] 7. 客户端（Cherry Studio / Claude Desktop）配置 `command: java` + args `-Dspring.ai.mcp.server.stdio=true -jar xxx.jar`
- [ ] 8. 客户端打开，AI 会自动发现你的工具并调用 🎉

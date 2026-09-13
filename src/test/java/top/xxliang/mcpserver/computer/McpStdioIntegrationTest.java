package top.xxliang.mcpserver.computer;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * stdio 端到端集成测试。
 * 直接用 ProcessBuilder 启动 mcp-server-computer 的 jar，
 * 通过 MCP SDK 的 StdioClientTransport 与其进行 JSON-RPC 通信，
 * 验证 initialize → tools/list → tools/call 完整链路。
 * <p>
 * 这个测试不启动 Spring 容器，完全独立于主应用，直接测试 MCP Server 进程的协议级行为。
 */
public class McpStdioIntegrationTest {

    private static final String SERVER_JAR = "/Users/xxliang/个人资料/ddd_project/ai-knowledge/mcp-server-computer/target/mcp-server-computer-1.0-SNAPSHOT.jar";

    private McpSyncClient client;
    private McpSchema.InitializeResult initResult;

    @BeforeEach
    void setUp() {
        ServerParameters params = ServerParameters.builder("java")
                .args("-Dspring.ai.mcp.server.stdio=true", "-jar", SERVER_JAR)
                .build();

        StdioClientTransport transport = new StdioClientTransport(params);

        client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .clientInfo(new McpSchema.Implementation("test-client", "1.0.0"))
                .build();

        // initialize() 返回 InitializeResult, 包含 capabilities + server 信息
        initResult = client.initialize();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void handshake_shouldDeclareToolsCapability() {
        System.out.println("Server name: " + client.getServerInfo().name());
        System.out.println("Server version: " + client.getServerInfo().version());
        System.out.println("Protocol version: " + initResult.protocolVersion());
        System.out.println("Capabilities: " + initResult.capabilities());

        assertNotNull(initResult, "初始化结果不应为 null");
        assertNotNull(initResult.capabilities(), "Capabilities 不应为 null");
        // tools capability 必须存在——这是之前启动失败的根因
        assertNotNull(initResult.capabilities().tools(), "必须声明 tools capability");
    }

    @Test
    void toolsList_shouldReturnQueryConfig() {
        McpSchema.ListToolsResult result = client.listTools();

        assertNotNull(result, "listTools 返回不应为 null");
        assertNotNull(result.tools(), "tools 列表不应为 null");
        assertFalse(result.tools().isEmpty(), "tools 列表不应为空");

        System.out.println("\n===== 发现的 MCP Tools =====");
        for (McpSchema.Tool tool : result.tools()) {
            System.out.println("  - " + tool.name() + ": " + tool.description());
        }

        // 确认我们注册的 queryConfig 在里面
        boolean found = result.tools().stream()
                .anyMatch(t -> "queryConfig".equals(t.name()));
        assertTrue(found, "应包含 queryConfig tool");
    }

    @Test
    void toolsCall_queryConfig_shouldReturnOsInfo() {
        // 先确认有这个 tool
        McpSchema.ListToolsResult listResult = client.listTools();
        assertTrue(
                listResult.tools().stream().anyMatch(t -> "queryConfig".equals(t.name())),
                "queryConfig 必须存在才能调用"
        );

        // 扁平化参数: {"computer": "xxliang"}
        McpSchema.CallToolResult callResult = client.callTool(new McpSchema.CallToolRequest(
                "queryConfig",
                Map.of("computer", "xxliang")
        ));

        assertNotNull(callResult, "callTool 返回不应为 null");
        assertNotNull(callResult.content(), "content 不应为 null");
        assertFalse(callResult.content().isEmpty(), "content 不应为空");

        // 打印结果
        System.out.println("\n===== queryConfig 调用结果 =====");
        callResult.content().forEach(c -> {
            if (c instanceof McpSchema.TextContent tc) {
                System.out.println(tc.text());
            } else {
                System.out.println(c);
            }
        });

        // 检查返回的文本里有我们期望的字段
        String resultText = callResult.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .findFirst()
                .orElse("");

        assertTrue(resultText.contains("osName"), "返回结果应包含 osName");
        assertTrue(resultText.contains("javaVersion"), "返回结果应包含 javaVersion");
    }
}

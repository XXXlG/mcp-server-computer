package top.xxliang.mcpserver.computer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 mcp-server-computer 的 Spring 上下文能正确注册 ToolCallbackProvider。
 * 这是 MCP Server 能在握手时声明 tools capability 的前提。
 */
@SpringBootTest
public class McpToolRegistrationTest {

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Test
    void toolCallbackProvider_shouldExist() {
        assertNotNull(toolCallbackProvider, "ToolCallbackProvider bean 必须存在");
    }

    @Test
    void toolCallbackProvider_shouldContainQueryConfigTool() {
        FunctionCallback[] callbacks = toolCallbackProvider.getToolCallbacks();
        assertNotNull(callbacks, "ToolCallback 数组不能为 null");
        assertTrue(callbacks.length >= 1, "至少应注册 1 个 tool, 实际: " + callbacks.length);

        boolean found = false;
        for (FunctionCallback callback : callbacks) {
            String name = callback.getName();
            String desc = callback.getDescription();
            System.out.println("已注册 tool: name=" + name + ", desc=" + desc);
            if ("queryConfig".equals(name)) {
                found = true;
            }
        }
        assertTrue(found, "应包含名为 'queryConfig' 的 tool");
    }
}

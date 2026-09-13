package top.xxliang.mcpserver.computer.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.xxliang.mcpserver.computer.domain.server.ComputerService;
import top.xxliang.mcpserver.computer.domain.server.FileSystemService;

@Configuration
public class McpToolConfig {

    /**
     * 注册所有 @Service 中带 @Tool 注解的方法为 MCP 工具。
     * <p>
     * 后续新增 Service（如 NetworkService、ProcessService），
     * 只需在这里追加 toolObjects(...) 即可自动纳入。
     */
    @Bean
    public ToolCallbackProvider mcpTools(
            ComputerService computerService,
            FileSystemService fileSystemService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(computerService, fileSystemService)
                .build();
    }
}

package top.xxliang.mcpserver.computer.domain.server;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 电脑信息查询工具集。
 */
@Slf4j
@Service
public class ComputerService {

    @Tool(description = "获取当前机器的操作系统、Java版本、用户目录等配置信息")
    public Map<String, Object> queryConfig(
            @ToolParam(description = "电脑名称，任意值，用于标识这次查询") String computer) {

        log.info("[queryConfig] 获取电脑配置: {}", computer);

        Map<String, Object> result = new LinkedHashMap<>();
        Properties props = System.getProperties();

        result.put("queryComputer", computer);
        result.put("osName", props.getProperty("os.name"));
        result.put("osVersion", props.getProperty("os.version"));
        result.put("osArch", props.getProperty("os.arch"));
        result.put("userName", props.getProperty("user.name"));
        result.put("userHome", props.getProperty("user.home"));
        result.put("userDir", props.getProperty("user.dir"));
        result.put("javaVersion", props.getProperty("java.version"));
        result.put("javaVendor", props.getProperty("java.vendor"));

        // 补充 OS 特定的硬件信息
        String osName = result.get("osName").toString().toLowerCase();
        try {
            if (osName.contains("mac")) {
                result.put("hardwareInfo", exec("system_profiler SPHardwareDataType"));
            } else if (osName.contains("win")) {
                result.put("hardwareInfo", exec("systeminfo"));
            } else if (osName.contains("nix") || osName.contains("nux")) {
                result.put("hardwareInfo", exec("lshw -short"));
            }
        } catch (Exception e) {
            log.warn("[queryConfig] 获取硬件信息失败", e);
        }

        return result;
    }

    private String exec(String command) throws Exception {
        Process process = Runtime.getRuntime().exec(command);
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString().trim();
        }
    }
}

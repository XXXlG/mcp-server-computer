package top.xxliang.mcpserver.computer.domain.server;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件系统操作工具集。
 * <p>
 * 权限策略：
 * <ul>
 *   <li><b>只读操作</b>（listDirectory / readFile）：<b>无安全限制</b>，可访问任意路径</li>
 *   <li><b>写操作</b>（createFile / appendFile）：<b>受安全根目录限制</b>，防止 AI 误写系统敏感位置</li>
 * </ul>
 * 安全根目录通过 {@code mcp.file.root} 配置，用户可通过 JVM 参数 -D 覆盖。
 */
@Slf4j
@Service
public class FileSystemService {

    /**
     * 写操作安全根目录，所有写操作必须在此目录内。
     */
    private final Path rootDir;

    public FileSystemService(
            @Value("${mcp.file.root:#{systemProperties['user.home'] + '/Documents/mcp-workspace'}}") String rootPath) {
        this.rootDir = Path.of(rootPath).toAbsolutePath().normalize();
        ensureRootDirExists();
    }

    private void ensureRootDirExists() {
        try {
            Files.createDirectories(rootDir);
            log.info("[FileSystemService] 写操作安全根目录: {}（只读操作不受此限制）", rootDir);
        } catch (IOException e) {
            log.error("[FileSystemService] 创建根目录失败: {}", rootDir, e);
            throw new IllegalStateException("无法创建文件操作根目录: " + rootDir, e);
        }
    }

    // ====================== 路径解析方法 ======================

    /**
     * 只读路径解析：只处理相对路径 → 绝对路径的转换，<b>不做安全校验</b>。
     * 相对路径基于当前工作目录（user.dir）解析。
     */
    private Path resolvePathForRead(String inputPath) {
        if (inputPath == null || inputPath.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        Path input = Path.of(inputPath);
        return input.isAbsolute()
                ? input.toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.dir")).resolve(input).normalize();
    }

    /**
     * 写操作路径解析：相对路径基于 rootDir，且 <b>必须在 rootDir 范围内</b>。
     */
    private Path resolvePathForWrite(String inputPath) {
        if (inputPath == null || inputPath.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }

        Path input = Path.of(inputPath);
        Path resolved;

        if (input.isAbsolute()) {
            resolved = input.toAbsolutePath().normalize();
        } else {
            resolved = rootDir.resolve(input).normalize();
        }

        if (!resolved.startsWith(rootDir)) {
            throw new SecurityException("写入被拦截：目标路径 " + resolved
                    + " 超出允许范围 " + rootDir);
        }

        return resolved;
    }

    // ====================== Tool 1: 查看目录（只读，无限制） ======================

    @Tool(description = "列出指定目录下的文件和子目录，返回每项的类型、大小和名称。" +
            "支持绝对路径或相对路径。可访问任意目录，不受安全限制。")
    public String listDirectory(
            @ToolParam(description = "目录路径。如 '/Users/xxliang' 或 'D:\\work' 或 './src'")
            String path,
            @ToolParam(description = "是否递归显示子目录内容，默认 false")
            boolean recursive) {

        log.info("[listDirectory] path={}, recursive={}", path, recursive);

        try {
            Path dir = resolvePathForRead(path);

            if (!Files.exists(dir)) {
                return "错误：路径不存在: " + dir;
            }
            if (!Files.isDirectory(dir)) {
                return "错误：路径不是目录而是文件: " + dir;
            }

            List<String> lines = new ArrayList<>();
            lines.add("📁 目录: " + dir);
            lines.add("══════════════════════════════════════");

            try (Stream<Path> walk = recursive ? Files.walk(dir) : Files.list(dir)) {
                List<Path> paths = walk.sorted().toList();

                for (Path p : paths) {
                    BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                    String relPath = dir.relativize(p).toString();

                    if (attrs.isDirectory()) {
                        lines.add(String.format("  📂 %s/", relPath));
                    } else {
                        lines.add(String.format("  📄 %s  (%d bytes, %s)",
                                relPath, attrs.size(), formatTime(attrs.lastModifiedTime().toMillis())));
                    }
                }
            }

            if (lines.size() == 2) {
                lines.add("  （空目录）");
            }

            return String.join("\n", lines);

        } catch (IOException e) {
            log.error("[listDirectory] 读取失败", e);
            return "❌ 读取目录失败: " + e.getMessage();
        } catch (SecurityException e) {
            log.warn("[listDirectory] 权限拦截: {}", e.getMessage());
            return "⛔ " + e.getMessage();
        }
    }

    // ====================== Tool 2: 读取文件（只读，无限制） ======================

    @Tool(description = "读取指定文本文件的全部内容。" +
            "支持 .txt / .md / .log / .java / .yml 等常见文本格式。" +
            "可读取任意路径的文件，不受安全限制。")
    public String readFile(
            @ToolParam(description = "文件路径。支持绝对路径或相对路径")
            String filePath,
            @ToolParam(description = "最大读取字符数，超过则截断并提示。默认 50000")
            int maxChars) {

        log.info("[readFile] filePath={}, maxChars={}", filePath, maxChars);

        try {
            Path file = resolvePathForRead(filePath);

            if (!Files.exists(file)) {
                return "❌ 文件不存在: " + file;
            }
            if (Files.isDirectory(file)) {
                return "❌ 路径是目录不是文件: " + file;
            }

            // 跳过明显的二进制文件
            String fileName = file.getFileName().toString().toLowerCase();
            if (fileName.endsWith(".png") || fileName.endsWith(".jpg")
                    || fileName.endsWith(".zip") || fileName.endsWith(".jar")
                    || fileName.endsWith(".class") || fileName.endsWith(".exe")) {
                return "❌ 不支持读取二进制文件: " + fileName;
            }

            String content = Files.readString(file, StandardCharsets.UTF_8);

            if (content.length() > maxChars) {
                content = content.substring(0, maxChars)
                        + "\n\n... ⚠️ 已截断。文件总长度 " + content.length()
                        + " 字符，本次读取 " + maxChars + " 字符";
            }

            log.info("[readFile] 成功读取 {}, 内容长度 {} chars", file, content.length());
            return content;

        } catch (IOException e) {
            log.error("[readFile] 读取失败", e);
            return "❌ 读取文件失败: " + e.getMessage();
        }
    }

    // ====================== Tool 3: 创建/覆盖文件（写，受限制） ======================

    @Tool(description = "创建新文件或覆盖已有文件。" +
            "如果文件不存在则自动创建（包括必要的父目录）；" +
            "如果文件已存在则直接覆盖内容。" +
            "⚠️ 写入操作受安全限制，只能写入配置的根目录（默认 ~/Documents/mcp-workspace）。")
    public String createFile(
            @ToolParam(description = "目标文件路径。相对路径基于写操作根目录解析")
            String filePath,
            @ToolParam(description = "要写入的文件完整内容")
            String content) {

        log.info("[createFile] filePath={}, contentLength={}", filePath,
                content != null ? content.length() : 0);

        try {
            Path file = resolvePathForWrite(filePath);

            Path parent = file.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                log.info("[createFile] 自动创建父目录: {}", parent);
            }

            boolean existed = Files.exists(file);
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            String action = existed ? "覆盖更新" : "新建";
            return String.format("✅ 文件已%s: %s (%d 字符)", action, file, content != null ? content.length() : 0);

        } catch (SecurityException e) {
            log.warn("[createFile] 安全拦截: {}", e.getMessage());
            return "⛔ " + e.getMessage();
        } catch (IOException e) {
            log.error("[createFile] 写入失败", e);
            return "❌ 写入文件失败: " + e.getMessage();
        }
    }

    // ====================== Tool 4: 追加写入文件（写，受限制） ======================

    @Tool(description = "向已有文件末尾追加内容。" +
            "如果文件不存在则先创建。" +
            "适用于持续写入日志、多条汇总等场景。" +
            "⚠️ 写入操作受安全限制，只能写入配置的根目录。")
    public String appendFile(
            @ToolParam(description = "目标文件路径。相对路径基于写操作根目录解析")
            String filePath,
            @ToolParam(description = "要追加的内容")
            String content,
            @ToolParam(description = "追加前是否先加一个换行符，默认 true")
            boolean prependNewline) {

        log.info("[appendFile] filePath={}, contentLength={}", filePath,
                content != null ? content.length() : 0);

        try {
            Path file = resolvePathForWrite(filePath);

            Path parent = file.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            String toAppend = (prependNewline && Files.exists(file))
                    ? "\n" + (content == null ? "" : content)
                    : (content == null ? "" : content);

            Files.writeString(file, toAppend, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            return String.format("✅ 已追加到: %s (+%d 字符)", file, toAppend.length());

        } catch (SecurityException e) {
            log.warn("[appendFile] 安全拦截: {}", e.getMessage());
            return "⛔ " + e.getMessage();
        } catch (IOException e) {
            log.error("[appendFile] 追加失败", e);
            return "❌ 追加文件失败: " + e.getMessage();
        }
    }

    // ====================== 辅助方法 ======================

    private static String formatTime(long millis) {
        long diff = System.currentTimeMillis() - millis;
        if (diff < 60_000) return "刚刚";
        if (diff < 3_600_000) return (diff / 60_000) + "分钟前";
        if (diff < 86_400_000) return (diff / 3_600_000) + "小时前";
        return (diff / 86_400_000) + "天前";
    }
}

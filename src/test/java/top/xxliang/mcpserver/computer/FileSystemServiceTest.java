package top.xxliang.mcpserver.computer;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import top.xxliang.mcpserver.computer.domain.server.FileSystemService;

/**
 * FileSystemService 四个工具的手动测试。
 * <p>
 * 执行顺序：createFile → listDirectory → readFile → appendFile → 清理
 * 每个 @Test 方法独立，单独跑或全跑都可以。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class FileSystemServiceTest {

    @Autowired
    private FileSystemService fileSystemService;

    // ====== 1. createFile ======

    @Test
    @Order(1)
    void testCreateFile() {
        System.out.println("\n====== [1] createFile ======");

        // 创建主测试文件
        String result1 = fileSystemService.createFile(
                "./test-daily-report/2026-09-13.txt",
                "2026-09-13 日报\n" +
                        "================\n" +
                        "1. 完成了 MCP Server 的文件操作工具开发\n" +
                        "2. 新增 listDirectory / readFile / createFile / appendFile\n" +
                        "3. 加了路径安全校验，限制在 mcp.file.root 目录内\n" +
                        "4. 下午开了 2 个会\n"
        );
        System.out.println(result1);

        // 覆盖写测试（同一个文件再写一次）
        String result2 = fileSystemService.createFile(
                "./test-daily-report/2026-09-13.txt",
                "这是覆盖后的新内容，之前的日报内容已经被我覆盖了！"
        );
        System.out.println(result2);

        // 自动创建父目录
        String result3 = fileSystemService.createFile(
                "./test-daily-report/sub/dir/nested.txt",
                "嵌套目录自动创建测试"
        );
        System.out.println(result3);
    }

    // ====== 2. listDirectory ======

    @Test
    @Order(2)
    void testListDirectory() {
        System.out.println("\n====== [2] listDirectory ======");

        // 查看根目录
        System.out.println("--- 根目录 ---");
        String root = fileSystemService.listDirectory(".", false);
        System.out.println(root);

        // 查看 test-daily-report 目录
        System.out.println("\n--- test-daily-report 目录（非递归）---");
        String dir1 = fileSystemService.listDirectory("./test-daily-report", false);
        System.out.println(dir1);

        // 递归查看
        System.out.println("\n--- test-daily-report 目录（递归）---");
        String dir2 = fileSystemService.listDirectory("./test-daily-report", true);
        System.out.println(dir2);

        // 不存在的路径
        System.out.println("\n--- 不存在的路径 ---");
        String dir3 = fileSystemService.listDirectory("./not-exist-folder", false);
        System.out.println(dir3);
    }

    // ====== 3. readFile ======

    @Test
    @Order(3)
    void testReadFile() {
        System.out.println("\n====== [3] readFile ======");

        // 正常读取
        System.out.println("--- 读取 2026-09-13.txt ---");
        String content1 = fileSystemService.readFile("./test-daily-report/2026-09-13.txt", 50000);
        System.out.println(content1);

        // 截断读取
        System.out.println("\n--- 限制 maxChars=10 的截断读取 ---");
        String content2 = fileSystemService.readFile("./test-daily-report/2026-09-13.txt", 10);
        System.out.println(content2);

        // 不存在的文件
        System.out.println("\n--- 不存在的文件 ---");
        String content3 = fileSystemService.readFile("./test-daily-report/nothing.txt", 50000);
        System.out.println(content3);

        // 读目录（不是文件）
        System.out.println("\n--- 错误地读目录 ---");
        String content4 = fileSystemService.readFile("./test-daily-report", 50000);
        System.out.println(content4);

        // 路径越界测试
        System.out.println("\n--- 路径越界（安全拦截）---");
        String content5 = fileSystemService.readFile("../../../etc/passwd", 50000);
        System.out.println(content5);
    }

    // ====== 4. appendFile ======

    @Test
    @Order(4)
    void testAppendFile() {
        System.out.println("\n====== [4] appendFile ======");

        // 向已存在的文件追加
        System.out.println("--- 向 2026-09-13.txt 追加内容 ---");
        String result1 = fileSystemService.appendFile(
                "./test-daily-report/2026-09-13.txt",
                "\n这是 append 追加的第二段内容！",
                true
        );
        System.out.println(result1);

        // 追加后再读一遍验证
        System.out.println("\n--- 追加后重新读取验证 ---");
        String verify = fileSystemService.readFile("./test-daily-report/2026-09-13.txt", 50000);
        System.out.println(verify);

        // 向不存在的文件追加（自动创建）
        System.out.println("\n--- 向不存在的文件追加（自动创建）---");
        String result2 = fileSystemService.appendFile(
                "./test-daily-report/auto-create.txt",
                "这个文件原本不存在，append 帮我创建了",
                false
        );
        System.out.println(result2);
    }

    // ====== 清理测试数据（可选） ======

    @Test
    @Order(5)
    void testSecurityBoundary() {
        System.out.println("\n====== [附加] 路径安全校验测试 ======");

        // 尝试越界访问上层目录
        String result1 = fileSystemService.listDirectory("../../", false);
        System.out.println("listDirectory 越界: " + result1);

        String result2 = fileSystemService.readFile("../../../.bashrc", 50000);
        System.out.println("readFile 越界: " + result2);

        String result3 = fileSystemService.createFile("../../hacked.txt", "hacked!");
        System.out.println("createFile 越界: " + result3);
    }
}

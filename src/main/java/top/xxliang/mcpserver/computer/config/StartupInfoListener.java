package top.xxliang.mcpserver.computer.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

//@Component
@Deprecated //暂时删除
public class StartupInfoListener {

    private final Environment env;

    public StartupInfoListener(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void printStartupInfo() {
        String protocol = "http";
        if (env.getProperty("server.ssl.key-store") != null) {
            protocol = "https";
        }

        String port = env.getProperty("server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "");
        String hostAddress = "localhost";

        try {
            hostAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            // 忽略，使用默认值
        }

        System.out.println("\n----------------------------------------------------------\n" +
                "\t应用启动成功！访问地址如下：\n" +
                "\t本地访问: \t" + protocol + "://localhost:" + port + contextPath + "\n" +
                "\t外部访问: \t" + protocol + "://" + hostAddress + ":" + port + contextPath + "\n" +
                "\tSwagger文档: \t" + protocol + "://" + hostAddress + ":" + port + contextPath + "/swagger-ui.html\n" +
                "----------------------------------------------------------");
    }
}
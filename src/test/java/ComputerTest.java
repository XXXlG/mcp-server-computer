import com.alibaba.fastjson.JSON;
import top.xxliang.mcpserver.computer.domain.server.ComputerService;

import java.util.Map;

public class ComputerTest {

    public static void main(String[] args) {
        ComputerService computerService = new ComputerService();
        Map xxliang =  computerService.queryConfig("xxliang");
        System.out.println(JSON.toJSONString(xxliang));
    }
}

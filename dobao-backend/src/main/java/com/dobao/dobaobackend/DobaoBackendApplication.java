package com.dobao.dobaobackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 豆包智能体后端启动类
 */
@SpringBootApplication
@EnableAsync        // 面试转写等耗时任务走专用线程池，不占用 Tomcat 线程
@EnableScheduling   // 转写任务轮询续跑 + 30 天音频清理
public class DobaoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(DobaoBackendApplication.class, args);
        System.out.println("豆包智能体后端启动成功");
        String robotHead = """
      .-------.
     /  O   O  \\
    |     ^     |
    |    ___    |
     \\_________/
    """;
        System.out.println(robotHead);

    }

}

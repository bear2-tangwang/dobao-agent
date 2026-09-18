package com.dobao.dobaobackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 豆包智能体后端启动类
 */
@SpringBootApplication
public class DobaoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(DobaoBackendApplication.class, args);
        System.out.println("豆包智能体后端启动成功");
    }

}

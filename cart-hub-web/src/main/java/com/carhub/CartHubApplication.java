package com.carhub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.carhub")
@MapperScan("com.carhub.mapper")
@EnableAsync
@EnableScheduling
public class CartHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(CartHubApplication.class, args);
        System.out.println("====================================================");
        System.out.println("  购物车引擎 Cart-Hub-Engine 启动成功！");
        System.out.println("  API文档: http://localhost:8080/cart-hub/doc.html");
        System.out.println("====================================================");
    }

}

package com.activespace;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.activespace.mapper")
@SpringBootApplication
public class ActiveSpaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ActiveSpaceApplication.class, args);
    }

}

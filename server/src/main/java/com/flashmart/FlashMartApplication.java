package com.flashmart;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.flashmart.**.mapper")
@SpringBootApplication
public class FlashMartApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashMartApplication.class, args);
    }
}

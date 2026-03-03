package com.fileproc;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@MapperScan("com.fileproc.**.mapper")
public class FileProcessingApplication {
    public static void main(String[] args) {
        SpringApplication.run(FileProcessingApplication.class, args);
    }
}

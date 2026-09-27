package com.lrs.buddy;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Buddy 基础框架启动类。
 *
 * <p>开启 {@code @EnableScheduling}：系统监控的指标采样、在线台账清理等
 * 都依赖定时任务，框架层面统一开启，业务模块无需重复声明。
 */
@EnableScheduling
@MapperScan("com.lrs.buddy.modules.**.mapper")
@SpringBootApplication
public class BuddyApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuddyApplication.class, args);
    }
}

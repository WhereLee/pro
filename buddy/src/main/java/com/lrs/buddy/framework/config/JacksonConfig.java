package com.lrs.buddy.framework.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * Jackson 全局序列化规则。
 *
 * <p>两个关键点：
 * <ol>
 *   <li><b>Long 转 String</b>：ID 使用雪花算法（19 位），而 JavaScript 的 Number
 *       最大安全整数是 2^53-1，19 位数字传到前端会丢失精度
 *       （例如 1234567890123456789 变成 1234567890123456800）。
 *       统一把 Long 序列化为字符串，前端用字符串比较即可。
 *       这是前后端分离项目里非常典型的一个坑。</li>
 *   <li><b>时间格式</b>：统一 {@code yyyy-MM-dd HH:mm:ss}，并提供反序列化规则，
 *       保证前端传回的字符串也能正确解析为 LocalDateTime。</li>
 * </ol>
 */
@Configuration
public class JacksonConfig {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);

        return builder -> {
            builder.timeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            SimpleModule module = new SimpleModule();
            // Long / long 全部输出为字符串，规避前端大数精度丢失
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.modules(module);

            JavaTimeModule javaTimeModule = new JavaTimeModule();
            javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(formatter));
            javaTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(formatter));
            builder.modules(javaTimeModule);
        };
    }
}

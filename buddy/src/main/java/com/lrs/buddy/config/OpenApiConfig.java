package com.lrs.buddy.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档配置（springdoc-openapi / OpenAPI 3）。
 *
 * <p>说明：这里使用的是 springdoc 原生 Swagger UI，而不是 knife4j。
 * springfox（老项目常用的 Swagger 2 方案）自 2020 年起已停止维护，
 * 且不支持 Spring Boot 3 的 jakarta 命名空间，必须替换。
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI buddyOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Buddy 接口文档")
                        .version("1.0.0")
                        .description("可复用的企业级 Spring Boot 基础框架"))
                // 声明全局使用 Bearer Token，文档页面可以直接填入 token 调试
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")));
    }

    /**
     * 只扫描业务包，避免把 Spring Boot / Error 等框架自带接口也扫进文档。
     */
    @Bean
    public GroupedOpenApi systemApi() {
        return GroupedOpenApi.builder()
                .group("系统管理")
                .pathsToMatch("/api/**")
                .packagesToScan("com.lrs.buddy.modules")
                .build();
    }
}

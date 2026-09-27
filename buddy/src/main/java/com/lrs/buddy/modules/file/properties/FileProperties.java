package com.lrs.buddy.modules.file.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件存储配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "buddy.file")
public class FileProperties {

    /** 本地存储根目录 */
    private String path = "./uploads";

    /** 单文件大小上限（字节），默认 20MB */
    private long maxSize = 20 * 1024 * 1024L;

    /**
     * 允许上传的扩展名白名单。
     *
     * <p>用白名单而不是黑名单：黑名单永远列不全（.php、.jsp、.jspx、.phtml…），
     * 只要漏一个就可能上传可执行脚本。只允许业务真正需要的类型才是稳妥做法。
     */
    private List<String> allowedExtensions = List.of(
            "jpg", "jpeg", "png", "gif", "bmp", "webp",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "csv", "zip", "rar"
    );
}

package com.lrs.buddy.modules.file.model;

import lombok.Builder;
import lombok.Data;

/**
 * 文件上传结果。
 *
 * @param originalName 原始文件名（仅用于展示，不可用于拼路径）
 * @param storedName   实际落盘的文件名（随机生成，不含用户输入）
 * @param relativePath 相对存储根目录的路径，删除与下载都用它
 * @param size         字节数
 */
@Data
@Builder
public class StoredFile {

    private String originalName;
    private String storedName;
    private String relativePath;
    private String contentType;
    private long size;
}

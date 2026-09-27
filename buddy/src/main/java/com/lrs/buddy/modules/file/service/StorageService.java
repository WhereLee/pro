package com.lrs.buddy.modules.file.service;

import com.lrs.buddy.modules.file.model.StoredFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储抽象。
 *
 * <p>定义接口而不是直接写本地磁盘实现，是为了将来接对象存储（OSS/COS/MinIO）
 * 时只新增一个实现类，业务代码一行不改。
 * 这正是依赖倒置的实用价值——不是为了炫技，而是隔离"会变的那部分"。
 */
public interface StorageService {

    /**
     * 上传文件。
     *
     * @param file      上传的文件
     * @param directory 子目录（业务模块名），如 avatar、notice
     */
    StoredFile upload(MultipartFile file, String directory);

    /**
     * 按相对路径删除文件。
     *
     * @return true 表示文件存在且已删除
     */
    boolean delete(String relativePath);

    /**
     * 读取文件内容（下载用）。
     *
     * @return null 表示文件不存在
     */
    byte[] load(String relativePath);
}

package com.lrs.buddy.framework.modules.file.service.impl;

import com.lrs.buddy.framework.common.exception.BusinessException;
import com.lrs.buddy.framework.modules.file.model.StoredFile;
import com.lrs.buddy.framework.modules.file.properties.FileProperties;
import com.lrs.buddy.framework.modules.file.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 本地磁盘存储实现。
 */
@Slf4j
@Service
public class LocalStorageServiceImpl implements StorageService {

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final Path rootPath;
    private final FileProperties properties;

    public LocalStorageServiceImpl(FileProperties properties) throws IOException {
        this.properties = properties;
        this.rootPath = Paths.get(properties.getPath()).toAbsolutePath().normalize();
        Files.createDirectories(rootPath);
        log.info("文件存储根目录：{}", rootPath);
    }

    @Override
    public StoredFile upload(MultipartFile file, String directory) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件为空");
        }
        if (file.getSize() > properties.getMaxSize()) {
            throw new BusinessException("文件大小超过限制（最大 "
                    + properties.getMaxSize() / 1024 / 1024 + "MB）");
        }

        String originalName = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename());
        String extension = extensionOf(originalName);
        validateExtension(extension);

        // 落盘文件名完全由服务端生成，不使用用户提供的任何字符，
        // 从根本上杜绝路径穿越（../../）与特殊字符导致的问题
        String storedName = UUID.randomUUID().toString().replace("-", "") + extension;
        String datePath = LocalDate.now().format(DATE_PATH);
        String safeDirectory = StringUtils.hasText(directory) ? directory : "common";

        Path target = rootPath.resolve(safeDirectory).resolve(datePath).resolve(storedName).normalize();
        // 兜底校验：即便目录参数被恶意传入 "../.." 也不会逃出根目录
        if (!target.startsWith(rootPath)) {
            throw new BusinessException("非法的文件存储路径");
        }

        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            log.error("文件写入失败：{}", target, e);
            throw new BusinessException("文件写入失败");
        }

        return StoredFile.builder()
                .originalName(originalName)
                .storedName(storedName)
                .relativePath(rootPath.relativize(target).toString().replace("\\", "/"))
                .contentType(file.getContentType())
                .size(file.getSize())
                .build();
    }

    @Override
    public boolean delete(String relativePath) {
        Path target = resolveSafely(relativePath);
        if (target == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("文件删除失败：{}", relativePath, e);
            return false;
        }
    }

    @Override
    public byte[] load(String relativePath) {
        Path target = resolveSafely(relativePath);
        if (target == null || !Files.exists(target)) {
            return null;
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            log.warn("文件读取失败：{}", relativePath, e);
            return null;
        }
    }

    /**
     * 把相对路径还原成绝对路径，并确保不会逃出根目录。
     *
     * @return 合法返回 Path，非法返回 null
     */
    private Path resolveSafely(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            return null;
        }
        Path target = rootPath.resolve(relativePath).normalize();
        return target.startsWith(rootPath) ? target : null;
    }

    private void validateExtension(String extension) {
        if (extension.isEmpty()) {
            throw new BusinessException("不允许上传无扩展名的文件");
        }
        List<String> allowed = properties.getAllowedExtensions();
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        String lower = extension.substring(1).toLowerCase(Locale.ROOT);
        if (!allowed.contains(lower)) {
            throw new BusinessException("不支持的文件类型：" + lower);
        }
    }

    private static String extensionOf(String filename) {
        int index = filename.lastIndexOf('.');
        return index < 0 ? "" : filename.substring(index);
    }
}

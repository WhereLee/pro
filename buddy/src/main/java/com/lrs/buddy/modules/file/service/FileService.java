package com.lrs.buddy.modules.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lrs.buddy.common.BusinessException;
import com.lrs.buddy.common.PageResult;
import com.lrs.buddy.modules.file.entity.SysFile;
import com.lrs.buddy.modules.file.mapper.SysFileMapper;
import com.lrs.buddy.modules.file.model.StoredFile;
import com.lrs.buddy.modules.file.model.query.FileQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Objects;

/**
 * 文件服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService extends ServiceImpl<SysFileMapper, SysFile> {

    /** 默认存储实现；将来接对象存储时换成对应实现即可 */
    private final StorageService storageService;

    public SysFile upload(MultipartFile file, String directory) {
        StoredFile stored = storageService.upload(file, directory);

        SysFile entity = new SysFile();
        entity.setOriginalName(stored.getOriginalName());
        entity.setStoredName(stored.getStoredName());
        entity.setRelativePath(stored.getRelativePath());
        entity.setContentType(stored.getContentType());
        entity.setSize(stored.getSize());
        entity.setDirectory(StringUtils.hasText(directory) ? directory : "common");
        entity.setStorageType("local");
        save(entity);
        return entity;
    }

    public PageResult<SysFile> pageFiles(FileQuery query) {
        LambdaQueryWrapper<SysFile> wrapper = new LambdaQueryWrapper<SysFile>()
                .like(StringUtils.hasText(query.getOriginalName()), SysFile::getOriginalName, query.getOriginalName())
                .eq(StringUtils.hasText(query.getDirectory()), SysFile::getDirectory, query.getDirectory())
                .orderByDesc(SysFile::getId);

        IPage<SysFile> page = page(query.toPage(), wrapper);
        return PageResult.of(page);
    }

    /**
     * 删除文件记录并删除磁盘文件。
     *
     * <p>顺序：先删记录再删文件。
     * 反过来（先删文件）若中途失败，会留下指向不存在文件的"悬空记录"。
     */
    public void removeFiles(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (Long id : ids) {
            SysFile file = getById(id);
            if (file == null) {
                continue;
            }
            removeById(id);
            try {
                storageService.delete(file.getRelativePath());
            } catch (Exception e) {
                // 记录已删、磁盘文件残留，只记录日志，后续可用清理任务补偿
                log.warn("磁盘文件删除失败，path={}：{}", file.getRelativePath(), e.getMessage());
            }
        }
    }

    public byte[] loadFile(Long id) {
        SysFile file = getById(id);
        if (file == null) {
            throw new BusinessException("文件不存在");
        }
        byte[] content = storageService.load(file.getRelativePath());
        if (content == null) {
            throw new BusinessException("文件已丢失");
        }
        return content;
    }
}

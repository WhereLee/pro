package com.lrs.buddy.framework.modules.file.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lrs.buddy.framework.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 上传文件记录。
 *
 * <p>文件本身存在磁盘（或对象存储）里，数据库只存元信息，
 * 这样列表查询、权限判断、删除都不需要碰文件系统。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_file")
public class SysFile extends BaseEntity {

    /** 原始文件名，仅用于展示 */
    private String originalName;

    /** 落盘文件名，由服务端生成 */
    private String storedName;

    /** 相对存储根目录的路径 */
    private String relativePath;

    private String contentType;

    /** 字节数 */
    private Long size;

    /** 存储方式：local / oss / cos … 便于将来混合存储 */
    private String storageType;

    /** 业务分类目录 */
    private String directory;
}

package com.lrs.buddy.modules.file.controller;

import com.lrs.buddy.common.PageResult;
import com.lrs.buddy.common.R;
import com.lrs.buddy.modules.file.entity.SysFile;
import com.lrs.buddy.modules.file.model.query.FileQuery;
import com.lrs.buddy.modules.file.service.FileService;
import com.lrs.buddy.modules.log.annotation.OperateLog;
import com.lrs.buddy.modules.log.enums.BusinessType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文件接口。
 */
@Tag(name = "文件管理")
@RestController
@RequestMapping("/sys/file")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    /**
     * 上传。
     *
     * <p>不进行防重复提交限流：用户主动选择文件上传，重复概率低，
     * 加了反而会影响批量上传的正常体验。
     */
    @Operation(summary = "上传文件")
    @OperateLog(title = "文件管理", businessType = BusinessType.INSERT, logParam = false)
    @PreAuthorize("hasAuthority('sys:file:upload')")
    @PostMapping("/upload")
    public R<SysFile> upload(@RequestParam("file") MultipartFile file,
                             @RequestParam(value = "directory", required = false) String directory) {
        return R.ok(fileService.upload(file, directory), "上传成功");
    }

    @Operation(summary = "文件分页列表")
    @PreAuthorize("hasAuthority('sys:file:list')")
    @PostMapping("/page")
    public R<PageResult<SysFile>> page(@Valid @RequestBody FileQuery query) {
        return R.ok(fileService.pageFiles(query));
    }

    @Operation(summary = "删除文件（支持批量）")
    @OperateLog(title = "文件管理", businessType = BusinessType.DELETE)
    @PreAuthorize("hasAuthority('sys:file:remove')")
    @DeleteMapping
    public R<Void> remove(@RequestBody List<Long> ids) {
        fileService.removeFiles(ids);
        return R.ok(null, "删除成功");
    }

    /**
     * 下载。
     *
     * <p>按 ID 而不是按路径下载：路径由客户端传入就有穿越风险，
     * 用 ID 查库拿路径则完全由服务端控制。
     *
     * <p>需要权限校验：否则任何登录用户都能枚举 ID 下载他人上传的文件。
     */
    @Operation(summary = "下载文件")
    @PreAuthorize("hasAuthority('sys:file:list')")
    @GetMapping("/download/{id}")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        SysFile file = fileService.getById(id);
        byte[] content = fileService.loadFile(id);

        String filename = URLEncoder.encode(file.getOriginalName(), StandardCharsets.UTF_8);
        MediaType mediaType = file.getContentType() != null
                ? MediaType.parseMediaType(file.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + filename)
                .body(content);
    }
}

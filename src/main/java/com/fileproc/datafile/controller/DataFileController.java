package com.fileproc.datafile.controller;

import com.fileproc.common.R;
import com.fileproc.datafile.entity.DataFile;
import com.fileproc.datafile.service.DataFileService;
import com.fileproc.datafile.service.DataSourceSchemaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 数据文件接口
 * GET    /data-files              - 按企业+年度查询
 * POST   /data-files              - 上传（multipart）
 * DELETE /data-files/{id}         - 删除
 * GET    /data-files/check        - 检查年度是否有数据
 * GET    /data-files/download/{id} - 受鉴权文件下载（替代静态资源直接访问）
 */
@Tag(name = "数据文件")
@Validated
@RestController
@RequestMapping("/data-files")
@RequiredArgsConstructor
public class DataFileController {

    private final DataFileService dataFileService;
    private final DataSourceSchemaService dataSourceSchemaService;

    @Operation(summary = "查询数据文件列表")
    @PreAuthorize("hasAuthority('file:list')")
    @GetMapping
    public R<List<DataFile>> list(
            @RequestParam String companyId,
            @RequestParam(required = false) Integer year) {
        return R.ok(dataFileService.listByCompanyAndYear(companyId, year));
    }

    @Operation(summary = "检查年度数据文件")
    @PreAuthorize("hasAuthority('file:list')")
    @GetMapping("/check")
    public R<Map<String, Object>> check(
            @RequestParam String companyId,
            @RequestParam int year) {
        return R.ok(dataFileService.checkByYear(companyId, year));
    }

    @Operation(summary = "上传数据文件")
    @PreAuthorize("hasAuthority('file:upload')")
    @PostMapping(consumes = "multipart/form-data")
    public R<DataFile> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam String companyId,
            @RequestParam(required = false) String name,
            @Pattern(regexp = "^(list|bvd)$", message = "文件类型只能为 list 或 bvd")
            @RequestParam String type,
            @Min(value = 2000, message = "年度不能早于2000年")
            @Max(value = 2100, message = "年度不能晚于2100年")
            @RequestParam int year) {
        return R.ok(dataFileService.upload(file, companyId, name, type, year));
    }

    @Operation(summary = "删除数据文件")
    @PreAuthorize("hasAuthority('file:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        dataFileService.delete(id);
        return R.ok();
    }

    @Operation(summary = "下载数据文件（受鉴权，含租户归属校验）")
    @PreAuthorize("hasAuthority('file:list')")
    @GetMapping("/download/{id}")
    public void download(@PathVariable String id, HttpServletResponse response) throws IOException {
        DataFileService.DownloadInfo info = dataFileService.getFileForDownload(id);
        Path filePath = info.getPath();
        String fileName = URLEncoder.encode(info.getName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + fileName);
        response.setContentLengthLong(Files.size(filePath));
        try (InputStream in = Files.newInputStream(filePath);
             OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    @Operation(summary = "触发解析数据文件的Sheet/字段结构（按需调用，结果持久化缓存）")
    @PreAuthorize("hasAuthority('file:list')")
    @PostMapping("/{id}/parse-schema")
    public R<List<DataSourceSchemaService.SheetNode>> parseSchema(@PathVariable String id) {
        return R.ok(dataSourceSchemaService.parseSchema(id));
    }

    @Operation(summary = "查询数据文件的Schema（已解析则直接返回，未解析返回空列表）")
    @PreAuthorize("hasAuthority('file:list')")
    @GetMapping("/{id}/schema")
    public R<List<DataSourceSchemaService.SheetNode>> getSchema(@PathVariable String id) {
        return R.ok(dataSourceSchemaService.getSchemaTree(id));
    }
}

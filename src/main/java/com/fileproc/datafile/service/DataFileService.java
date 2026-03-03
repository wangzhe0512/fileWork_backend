package com.fileproc.datafile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fileproc.common.BizException;
import com.fileproc.common.TenantContext;
import com.fileproc.common.annotation.OperationLog;
import com.fileproc.common.util.FileUtil;
import com.fileproc.datafile.entity.DataFile;
import com.fileproc.datafile.mapper.DataFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据文件 Service：上传（本地存储）、查询、删除、年度检查
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataFileService {

    private final DataFileMapper dataFileMapper;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    /** 按企业+年度查询数据文件列表 */
    public List<DataFile> listByCompanyAndYear(String companyId, Integer year) {
        LambdaQueryWrapper<DataFile> wrapper = new LambdaQueryWrapper<DataFile>()
                .eq(DataFile::getCompanyId, companyId)
                .orderByDesc(DataFile::getUploadAt);
        if (year != null) {
            wrapper.eq(DataFile::getYear, year);
        }
        return dataFileMapper.selectList(wrapper);
    }

    /** 检查指定年度是否已有数据文件 */
    public Map<String, Object> checkByYear(String companyId, int year) {
        int count = dataFileMapper.countByCompanyAndYear(companyId, year);
        return Map.of("hasData", count > 0, "count", count);
    }

    /** 允许上传的文件扩展名白名单 */
    private static final java.util.Set<String> ALLOWED_EXT =
            java.util.Set.of(".xlsx", ".xls", ".csv", ".pdf", ".doc", ".docx");

    /** 上传数据文件 */
    @OperationLog(module = "数据文件", action = "上传文件")
    public DataFile upload(MultipartFile file, String companyId, String name, String type, int year) {
        if (file == null || file.isEmpty()) throw BizException.of("请选择要上传的文件");

        String tenantId = TenantContext.getTenantId();
        String originalName = file.getOriginalFilename();
        String ext = (originalName != null && originalName.contains("."))
                ? originalName.substring(originalName.lastIndexOf('.')).toLowerCase()
                : "";

        // P0：文件类型白名单校验
        if (!ALLOWED_EXT.contains(ext)) {
            throw BizException.of("不支持的文件类型，仅允许：" + ALLOWED_EXT);
        }

        // 按 tenantId/companyId/yyyy/ 目录存储
        String dateDir = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy"));
        String relativePath = tenantId + "/" + companyId + "/" + dateDir + "/";
        String fileName = UUID.randomUUID() + ext;

        // P0：路径穿越防护
        Path baseDir = Paths.get(uploadDir).normalize().toAbsolutePath();
        Path fullDir = baseDir.resolve(relativePath).normalize();
        if (!fullDir.startsWith(baseDir)) {
            throw BizException.of("非法路径");
        }

        try {
            Files.createDirectories(fullDir);
            Path filePath = fullDir.resolve(fileName);
            file.transferTo(filePath);

            // 格式化文件大小
            long bytes = file.getSize();
            String sizeStr = FileUtil.formatSize(bytes);

            DataFile dataFile = new DataFile();
            dataFile.setId(UUID.randomUUID().toString());
            dataFile.setTenantId(tenantId);
            dataFile.setCompanyId(companyId);
            dataFile.setName(name != null && !name.isBlank() ? name : originalName);
            dataFile.setType(type);
            dataFile.setYear(year);
            dataFile.setSize(sizeStr);
            dataFile.setFilePath(relativePath + fileName);
            dataFile.setUploadAt(LocalDateTime.now());
            dataFileMapper.insert(dataFile);

            return dataFile;
        } catch (IOException e) {
            throw BizException.of("文件上传失败：" + e.getMessage());
        }
    }

    /** 删除数据文件 */
    @OperationLog(module = "数据文件", action = "删除文件")
    public void delete(String id) {
        DataFile dataFile = dataFileMapper.selectById(id);
        if (dataFile == null) throw BizException.notFound("数据文件");
        // P1：租户归属校验，防止越权删除
        String tenantId = TenantContext.getTenantId();
        if (!tenantId.equals(dataFile.getTenantId())) {
            throw BizException.forbidden("无权删除该文件");
        }
        // 先取路径，再删 DB 记录，避免记录删除后无法查询路径
        String relPath = dataFile.getFilePath();
        dataFileMapper.deleteById(id);
        // 删除物理文件，记录 warn 日志替代静默忽略
        if (relPath != null) {
            try {
                Path physical = Paths.get(uploadDir).resolve(relPath).normalize();
                if (physical.startsWith(Paths.get(uploadDir).normalize())) {
                    boolean deleted = Files.deleteIfExists(physical);
                    if (!deleted) log.warn("[DataFileService] 文件不存在，跳过物理删除: {}", physical);
                }
            } catch (IOException e) {
                log.warn("[DataFileService] 删除物理文件失败: {}", e.getMessage());
            }
        }
    }

    /** 根据路径获取真实 File 对象（供报告生成引擎使用），含路径穿越防护 */
    public File getPhysicalFile(String filePath) {
        Path base = Paths.get(uploadDir).normalize().toAbsolutePath();
        Path resolved = base.resolve(filePath).normalize();
        if (!resolved.startsWith(base)) {
            throw BizException.of("非法文件路径");
        }
        return resolved.toFile();
    }

    /**
     * 受鉴权文件下载：校验归属当前租户，返回下载所需信息
     */
    public DownloadInfo getFileForDownload(String id) {
        DataFile dataFile = dataFileMapper.selectById(id);
        if (dataFile == null) throw BizException.notFound("数据文件");
        String tenantId = TenantContext.getTenantId();
        if (!tenantId.equals(dataFile.getTenantId())) {
            throw BizException.forbidden("无权访问该文件");
        }
        String relPath = dataFileMapper.selectFilePathById(id);
        if (relPath == null) throw BizException.of("文件路径不存在");
        Path path = Paths.get(uploadDir).resolve(relPath).normalize();
        if (!path.startsWith(Paths.get(uploadDir).normalize())) {
            throw BizException.of("非法路径");
        }
        if (!Files.exists(path)) throw BizException.of("文件不存在");
        return new FileUtil.DownloadInfo(path, dataFile.getName());
    }

    /** 下载信息 DTO（已迁移至 FileUtil.DownloadInfo，此处保留类型别名供 Controller 向后兼容） */
    public static class DownloadInfo extends FileUtil.DownloadInfo {
        public DownloadInfo(java.nio.file.Path path, String name) {
            super(path, name);
        }
    }

    private String formatSize(long bytes) {
        return FileUtil.formatSize(bytes);
    }
}

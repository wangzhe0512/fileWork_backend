package com.fileproc.common.util;

import lombok.Getter;

import java.nio.file.Path;

/**
 * 文件工具类：格式化文件大小 + 下载信息 DTO
 */
public final class FileUtil {

    private FileUtil() {}

    /**
     * 将字节数格式化为可读字符串（B / KB / MB）
     */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /**
     * 文件下载信息 DTO（供 ReportService 和 DataFileService 共用）
     */
    @Getter
    public static class DownloadInfo {
        private final Path path;
        private final String name;

        public DownloadInfo(Path path, String name) {
            this.path = path;
            this.name = name;
        }
    }
}

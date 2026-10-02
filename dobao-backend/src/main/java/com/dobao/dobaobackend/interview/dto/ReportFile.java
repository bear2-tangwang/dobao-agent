package com.dobao.dobaobackend.interview.dto;

import java.io.InputStream;

/**
 * 报告文件（用于下载接口）。
 *
 * @param fileName    文件名，同时也是 MinIO 对象名
 * @param inputStream 文件内容流
 */
public record ReportFile(String fileName, InputStream inputStream) {
}

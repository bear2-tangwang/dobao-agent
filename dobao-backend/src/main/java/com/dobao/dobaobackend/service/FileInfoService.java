package com.dobao.dobaobackend.service;



import com.dobao.dobaobackend.entity.AiFileInfo;
import com.dobao.dobaobackend.entity.record.FileInfo;

import java.util.List;

/**
 * 文件信息服务接口
 */
public interface FileInfoService {

    /**
     * 保存文件信息
     */
    void saveFileInfo(FileInfo fileInfo);

    /**
     * 根据文件ID获取文件信息
     */
    FileInfo getFileInfoById(String fileId);

    /**
     * 按「文件ID + 归属用户」取文件信息。
     *
     * <p>凡是"用户提供了 fileId 就能取到内容"的接口都必须走这个重载：
     * {@code file_id} 是全局唯一索引，光按它查等于"猜到 ID 就能读别人的文件"。
     *
     * @return 文件不属于该用户时返回 null（调用方按"不存在"处理，不区分 403/404）
     */
    FileInfo getFileInfoById(String fileId, String userId);

    /**
     * 根据文件ID获取数据库实体
     */
    AiFileInfo getEntityById(String fileId);

    /**
     * 按「文件ID + 归属用户」取数据库实体（归属校验用）
     */
    AiFileInfo getEntityById(String fileId, String userId);

    /**
     * 更新文件信息
     */
    void updateFileInfo(FileInfo fileInfo);

    /**
     * 删除文件信息
     */
    void deleteFileInfo(String fileId);

    /**
     * 按「文件ID + 归属用户」删除文件信息（防止删掉别人的文件）
     */
    void deleteFileInfo(String fileId, String userId);

    /**
     * 检查文件是否存在
     */
    boolean exists(String fileId);

    /**
     * 获取所有文件列表
     */
    List<FileInfo> getAllFiles();

    /**
     * 取某用户的全部文件
     */
    List<FileInfo> getAllFiles(String userId);

    /**
     * 获取文件数量
     */
    int getFileCount();

    /**
     * 取某用户的文件数量
     */
    int getFileCount(String userId);
}

package com.dobao.dobaobackend.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dobao.dobaobackend.entity.AiFileInfo;
import com.dobao.dobaobackend.entity.record.FileInfo;
import com.dobao.dobaobackend.mapper.AiFileInfoMapper;
import com.dobao.dobaobackend.service.FileInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文件信息服务实现类
 */
@Service
@Slf4j
public class FileInfoServiceImpl extends ServiceImpl<AiFileInfoMapper, AiFileInfo> implements FileInfoService {

    @Override
    public void saveFileInfo(FileInfo fileInfo) {
        AiFileInfo entity = convertToEntity(fileInfo);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        this.save(entity);
        log.info("文件信息已保存: fileId={}", fileInfo.getFileId());
    }

    @Override
    public FileInfo getFileInfoById(String fileId) {
        AiFileInfo entity = getEntityById(fileId);
        if (entity == null) {
            return null;
        }
        return convertToDto(entity);
    }

    @Override
    public FileInfo getFileInfoById(String fileId, String userId) {
        AiFileInfo entity = getEntityById(fileId, userId);
        if (entity == null) {
            return null;
        }
        return convertToDto(entity);
    }

    @Override
    public AiFileInfo getEntityById(String fileId) {
        QueryWrapper<AiFileInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("file_id", fileId);
        return this.getOne(wrapper);
    }

    @Override
    public AiFileInfo getEntityById(String fileId, String userId) {
        QueryWrapper<AiFileInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("file_id", fileId);
        // userId 为空表示"没有用户维度"（历史调用方/后台任务），不做归属过滤
        if (StringUtils.hasText(userId)) {
            wrapper.eq("user_id", userId);
        }
        return this.getOne(wrapper);
    }

    @Override
    public void updateFileInfo(FileInfo fileInfo) {
        AiFileInfo entity = convertToEntity(fileInfo);
        entity.setUpdateTime(LocalDateTime.now());

        QueryWrapper<AiFileInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("file_id", fileInfo.getFileId());
        this.update(entity, wrapper);
        log.info("文件信息已更新: fileId={}", fileInfo.getFileId());
    }

    @Override
    public void deleteFileInfo(String fileId) {
        QueryWrapper<AiFileInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("file_id", fileId);
        this.remove(wrapper);
        log.info("文件信息已删除: fileId={}", fileId);
    }

    @Override
    public void deleteFileInfo(String fileId, String userId) {
        QueryWrapper<AiFileInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("file_id", fileId);
        if (StringUtils.hasText(userId)) {
            wrapper.eq("user_id", userId);
        }
        this.remove(wrapper);
        log.info("文件信息已删除: fileId={}, userId={}", fileId, userId);
    }

    @Override
    public boolean exists(String fileId) {
        QueryWrapper<AiFileInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("file_id", fileId);
        return this.count(wrapper) > 0;
    }

    @Override
    public List<FileInfo> getAllFiles() {
        return getAllFiles(null);
    }

    @Override
    public List<FileInfo> getAllFiles(String userId) {
        QueryWrapper<AiFileInfo> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(userId)) {
            wrapper.eq("user_id", userId);
        }
        List<AiFileInfo> entities = this.list(wrapper);
        return entities.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    public int getFileCount() {
        return Math.toIntExact(this.count());
    }

    @Override
    public int getFileCount(String userId) {
        if (!StringUtils.hasText(userId)) {
            return getFileCount();
        }
        QueryWrapper<AiFileInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        return Math.toIntExact(this.count(wrapper));
    }

    /**
     * 将DTO转换为实体
     */
    private AiFileInfo convertToEntity(FileInfo fileInfo) {
        AiFileInfo entity = new AiFileInfo();
        BeanUtils.copyProperties(fileInfo, entity);
        entity.setStatus(fileInfo.getStatus() != null ? fileInfo.getStatus().name() : "PENDING");
        return entity;
    }

    /**
     * 将实体转换为DTO
     */
    private FileInfo convertToDto(AiFileInfo entity) {
        FileInfo fileInfo = new FileInfo();
        BeanUtils.copyProperties(entity, fileInfo);
        // 转换状态字符串为枚举
        if (entity.getStatus() != null) {
            try {
                fileInfo.setStatus(FileInfo.FileStatus.valueOf(entity.getStatus()));
            } catch (IllegalArgumentException e) {
                log.warn("无法识别的文件状态: {}, 使用默认状态PENDING", entity.getStatus());
                fileInfo.setStatus(FileInfo.FileStatus.PENDING);
            }
        }
        return fileInfo;
    }
}

package com.dobao.dobaobackend.service;

import io.minio.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MinIO 对象存储服务
 * 负责文件的上传、下载、删除，以及存储桶的自动创建
 */
@Service
public class MinioService {

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucketName}")
    private String bucketName;

/*    @Value("${minio.endpoint}")
    private String endpoint; //Minio 服务端地址*/

    @Value("${minio.url}")
    private String endpoint; //Minio 对外访问地址前缀

    /**
     * 桶初始化标记（桶已存在 + 公共读策略已生效）。
     * 应用启动后只需成功初始化一次，避免每次上传都重复调用 bucketExists / setBucketPolicy。
     */
    private volatile boolean bucketReady = false;

    /**
     * 确保 bucket 存在，并按需把存储桶策略设置为公共读。
     *
     * <p>策略设置必须独立于"桶是否存在"判断之外：若写在 {@code if (!bucketExists)} 内部，
     * 桶一旦已存在（手工创建过、或先前版本创建过）公共读策略就永远不会生效，匿名访问返回 403。
     *
     * @param publicRead 是否将存储桶策略设置为公共读
     */
    private void createBucketIfNotExists(boolean publicRead) throws Exception {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }

            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }

            // 无论桶是新建的还是早已存在，都幂等地确保公共读策略已生效
            if (publicRead) {
                // 公共读策略：允许匿名 GetObject
                String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + bucketName + "/*\"]}]}";
                minioClient.setBucketPolicy(
                        SetBucketPolicyArgs.builder()
                                .bucket(bucketName)
                                .config(policy)
                                .build()
                );
            }

            bucketReady = true;
        }
    }

    /**
     * 上传文件（MultipartFile）
     *
     * @return 文件的完整访问URL
     */
    public String uploadFile(MultipartFile file, String objectName) throws Exception {
        createBucketIfNotExists(true);
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType())
                .build());
        // 去掉 endpoint 末尾斜杠，避免拼出双斜杠
        String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return String.format("%s/%s/%s", cleanEndpoint, bucketName, objectName);

    }

    /**
     * 上传文件（字节数组）
     *
     * @return 文件的完整访问URL
     */
    public String uploadFile(String objectName, byte[] content, String contentType) throws Exception {
        createBucketIfNotExists(true);
        try (InputStream stream = new ByteArrayInputStream(content)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(stream, content.length, -1)
                            .contentType(contentType)
                            .build()
            );

            // 去掉 endpoint 末尾斜杠，避免拼出双斜杠
        String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return String.format("%s/%s/%s", cleanEndpoint, bucketName, objectName);
        }
    }

    /**
     * 下载文件（返回 InputStream）
     */
    public InputStream downloadFile(String objectName) throws Exception {
        GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build());
        return response;
    }

    /**
     * 删除文件
     */
    public void deleteFile(String objectName) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .build());
    }
}

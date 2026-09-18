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

    @Value("${minio.endpoint}")
    private String endpoint; //Minio 服务端地址

    /**
     * 确保 bucket 存在，不存在则创建
     *
     * @param publicRead 是否将存储桶策略设置为公共读
     */
    private void createBucketIfNotExists(boolean publicRead) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
            // 不存在就创建 bucket
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());

            // 设置 bucket 策略为公共读
            if (publicRead) {
                /**
                 * 配置AWS S3 IAM 策略 JSON
                 * 允许所有用户读取 bucket 中的对象
                 * {
                 *   "Version": "2012-10-17",
                 *   "Statement": [
                 *     {
                 *       "Effect": "Allow",
                 *       "Principal": { "AWS": ["*"] },
                 *       "Action": ["s3:GetObject"],
                 *       "Resource": ["arn:aws:s3:::bucketName/*"]
                 *     }
                 *   ]
                 * }
                 */
                String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + bucketName + "/*\"]}]}";
                minioClient.setBucketPolicy(
                        SetBucketPolicyArgs.builder()
                                .bucket(bucketName)
                                .config(policy)
                                .build()
                );
            }
        }
    }

    /**
     * 上传文件（MultipartFile）
     *
     * @return 文件的完整访问URL
     */
    public String uploadFile(MultipartFile file, String objectName) throws Exception {
        createBucketIfNotExists(true);// 这里可根据你自己的情况改成false，如果改成false，需要在这个方法最后调一次getPresignedUrl
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType())
                .build());
        // 确保endpoint末尾没有斜杠，避免重复斜杠 http://127.0.0.1:9000/
        String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return String.format("%s/%s/%s", cleanEndpoint, bucketName, objectName); // 返回文件的完整URL ，例如：http://127.0.0.1:9000/dobao-agent/123.jpg

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

            // 确保endpoint末尾没有斜杠，避免重复斜杠
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

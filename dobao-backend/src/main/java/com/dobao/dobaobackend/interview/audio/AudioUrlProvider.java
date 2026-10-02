package com.dobao.dobaobackend.interview.audio;

/**
 * 音频 URL 提供者：把 MinIO 里的音频转成"百炼能拉取的 URL"。
 *
 * <p>百炼录音文件识别是异步任务式接口，官方明确不支持 Base64、不支持二进制流、不支持本地文件，
 * 只接受**公网可访问的 URL**。因此必须由本接口负责"让音频先有个 URL"。
 *
 * <p>两套实现按 {@code interview.asr.temp-upload-enabled} 切换（见编码方案 §3.3）：
 * <ul>
 *   <li>dev（true）：{@link DashScopeTempUrlProvider} —— 走百炼临时文件上传，本地开发无需公网域名</li>
 *   <li>prod（false）：{@link MinioPublicUrlProvider} —— 拼 MinIO 公网地址</li>
 * </ul>
 */
public interface AudioUrlProvider {

    /**
     * 为 MinIO 对象生成百炼可拉取的 URL。
     *
     * @param objectName MinIO 对象名（如 {@code file-xxx.m4a}），不是完整 URL
     * @return dev 返回 {@code oss://...} 临时地址；prod 返回 MinIO 公网 HTTPS 地址
     */
    String provide(String objectName);
}

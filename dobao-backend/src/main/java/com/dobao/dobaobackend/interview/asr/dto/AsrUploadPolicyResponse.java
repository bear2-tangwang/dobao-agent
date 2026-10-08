package com.dobao.dobaobackend.interview.asr.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 百炼"取上传凭证"接口的响应：{@code GET {base}/uploads?action=getPolicy&model=...}
 *
 * <p>外层包一层 {@code data}，故用本记录承载；只建模上传表单真正要用的字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AsrUploadPolicyResponse(Data data) {

    /**
     * 上传凭证与其附属信息
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            String policy,
            String signature,
            @JsonProperty("upload_dir") String uploadDir,
            @JsonProperty("upload_host") String uploadHost,
            @JsonProperty("oss_access_key_id") String ossAccessKeyId,
            @JsonProperty("x_oss_object_acl") String xOssObjectAcl,
            @JsonProperty("x_oss_forbid_overwrite") String xOssForbidOverwrite) {
    }

    /**
     * 凭证是否存在（字段齐备才可用）
     */
    public boolean usable() {
        return data != null
                && data.uploadHost() != null
                && data.policy() != null
                && data.signature() != null
                && data.ossAccessKeyId() != null;
    }
}

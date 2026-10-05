package com.dobao.dobaobackend.common;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * Agent通用响应类型
 * 用于统一各Agent的流式输出格式
 */
public class AgentResponse {

    /**
     * 支持的类型
     */
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_THINKING = "thinking";
    public static final String TYPE_REFERENCE = "reference";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_RECOMMEND = "recommend";

    private String type;
    private String content;
    private Integer count;
    private Object data;

    public AgentResponse() {
    }

    public AgentResponse(String type, String content) {
        this.type = type;
        this.content = content;
    }

    public AgentResponse(String type, String content, Integer count) {
        this.type = type;
        this.content = content;
        this.count = count;
    }

    public AgentResponse(String type, String content, Integer count, Object data) {
        this.type = type;
        this.content = content;
        this.count = count;
        this.data = data;
    }

    public static String text(String content) {
        return new AgentResponse(TYPE_TEXT, content).toJson();
    }

    public static String thinking(String content) {
        return new AgentResponse(TYPE_THINKING, content).toJson();
    }

    public static String reference(String content, Integer count) {
        return new AgentResponse(TYPE_REFERENCE, content, count).toJson();
    }

    /**
     * 未传 count 时按 content 里 JSON 数组的长度补上
     */
    public static String reference(String content) {
        try {
            var jsonArray = JSON.parseArray(content);
            if (jsonArray != null) {
                return reference(content, jsonArray.size());
            }
        } catch (Exception e) {
            // 解析失败：按无 count 处理
        }
        return reference(content, null);
    }

    public static String error(String content) {
        return new AgentResponse(TYPE_ERROR, content).toJson();
    }

    public static String recommend(String content) {
        return recommend(content, null);
    }

    public static String recommend(String content, Integer count) {
        return new AgentResponse(TYPE_RECOMMEND, content, count).toJson();
    }

    /**
     * 自定义类型；type 为 reference 且 content 是 JSON 数组时补 count
     */
    public static String json(String type, Object content) {
        if (TYPE_REFERENCE.equals(type) && content instanceof String jsonStr) {
            try {
                var jsonArray = JSON.parseArray(jsonStr);
                if (jsonArray != null && !jsonArray.isEmpty()) {
                    return reference(jsonStr, jsonArray.size());
                }
            } catch (Exception e) {
                // 解析失败：按普通 json 响应处理
            }
        }
        return new AgentResponse(type, content == null ? null : content.toString()).toJson();
    }

    public String toJson() {
        JSONObject obj = new JSONObject();
        obj.put("type", type);
        if (content != null) {
            obj.put("content", content);
        }
        if (count != null) {
            obj.put("count", count);
        }
        if (data != null) {
            if ((TYPE_REFERENCE.equals(type) || TYPE_RECOMMEND.equals(type)) && content != null) {
                try {
                    obj.put("content", JSON.parse(content));
                } catch (Exception e) {
                    obj.put("content", content);
                }
            } else {
                obj.put("data", data);
            }
        }
        return obj.toJSONString();
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }
}

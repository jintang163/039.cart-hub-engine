package com.carhub.common.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;

import java.util.List;
import java.util.Map;

public class JsonUtil {

    private JsonUtil() {
    }

    public static String toJson(Object obj) {
        return JSON.toJSONString(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return JSON.parseObject(json, clazz);
    }

    public static <T> T fromJson(String json, TypeReference<T> typeReference) {
        return JSON.parseObject(json, typeReference);
    }

    public static <T> List<T> fromJsonList(String json, Class<T> clazz) {
        return JSON.parseArray(json, clazz);
    }

    public static JSONObject parseObject(String json) {
        return JSON.parseObject(json);
    }

    public static Map<String, Object> toMap(Object obj) {
        return JSON.parseObject(toJson(obj), new TypeReference<Map<String, Object>>() {
        });
    }

    public static <T> Map<String, T> toMap(Object obj, Class<T> valueType) {
        return JSON.parseObject(toJson(obj), new TypeReference<Map<String, T>>() {
        });
    }

    public static <T> T fromMap(Map<String, Object> map, Class<T> clazz) {
        return parseObject(toJson(map)).toJavaObject(clazz);
    }

    public static Map<String, Object> fromJsonMap(String json) {
        return JSON.parseObject(json, new TypeReference<Map<String, Object>>() {
        });
    }

}

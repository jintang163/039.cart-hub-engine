package com.carhub.service;

import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.model.Cart;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioStorageService {

    private final MinioClient minioClient;
    private final CartHubProperties cartHubProperties;

    private static final DateTimeFormatter DIR_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    public String saveCartSnapshot(String tenantId, String snapshotId, Cart cart) {
        String bucket = cartHubProperties.getMinio().getBucketSnapshot();
        ensureBucketExists(bucket);
        String dir = LocalDateTime.now().format(DIR_FMT);
        String objectName = String.format("%s/%s/%s_%s.json", tenantId, dir, snapshotId, System.currentTimeMillis());
        try {
            String json = JsonUtil.toJson(cart);
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            InputStream stream = new ByteArrayInputStream(data);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(stream, data.length, -1)
                    .contentType("application/json")
                    .build());
            String url = buildUrl(bucket, objectName);
            log.info("saveCartSnapshot success: tenantId={}, snapshotId={}, url={}", tenantId, snapshotId, url);
            return url;
        } catch (Exception e) {
            log.error("saveCartSnapshot error", e);
            throw new RuntimeException("保存快照失败: " + e.getMessage());
        }
    }

    public Cart getCartSnapshot(String tenantId, String objectName) {
        String bucket = cartHubProperties.getMinio().getBucketSnapshot();
        try (InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectName)
                .build())) {
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return JsonUtil.fromJson(json, Cart.class);
        } catch (Exception e) {
            log.error("getCartSnapshot error", e);
            throw new RuntimeException("读取快照失败: " + e.getMessage());
        }
    }

    public String saveLog(String tenantId, String logType, String content) {
        String bucket = cartHubProperties.getMinio().getBucketLog();
        ensureBucketExists(bucket);
        String dir = LocalDateTime.now().format(DIR_FMT);
        String objectName = String.format("%s/%s/%s_%s.log", tenantId, dir, logType, System.currentTimeMillis());
        try {
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            InputStream stream = new ByteArrayInputStream(data);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(stream, data.length, -1)
                    .contentType("text/plain")
                    .build());
            return buildUrl(bucket, objectName);
        } catch (Exception e) {
            log.error("saveLog error", e);
            throw new RuntimeException("保存日志失败: " + e.getMessage());
        }
    }

    public boolean deleteObject(String bucket, String objectName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());
            return true;
        } catch (Exception e) {
            log.error("deleteObject error", e);
            return false;
        }
    }

    private void ensureBucketExists(String bucket) {
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket created: {}", bucket);
            }
        } catch (Exception e) {
            log.error("ensureBucketExists error", e);
            throw new RuntimeException("MinIO初始化失败: " + e.getMessage());
        }
    }

    private String buildUrl(String bucket, String objectName) {
        String endpoint = cartHubProperties.getMinio().getEndpoint();
        if (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        return String.format("%s/%s/%s", endpoint, bucket, objectName);
    }

}

package com.carhub.service;

import cn.afterturn.easypoi.excel.ExcelExportUtil;
import cn.afterturn.easypoi.excel.entity.ExportParams;
import cn.afterturn.easypoi.excel.entity.enmus.ExcelType;
import com.alibaba.fastjson.JSON;
import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.common.context.CartContextHolder;
import com.carhub.common.exception.BusinessException;
import com.carhub.common.result.ResultCode;
import com.carhub.common.util.JsonUtil;
import com.carhub.domain.dto.CartImportDTO;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.domain.vo.ImportTaskVO;
import com.carhub.service.excel.CartItemExcelVO;
import com.carhub.service.excel.ImportErrorReportVO;
import com.carhub.storage.CartRedisStorage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Workbook;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartImportExportService {

    private final CartRedisStorage cartRedisStorage;
    private final CartImportAsyncService cartImportAsyncService;
    private final RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final int TASK_EXPIRE_HOURS = 24;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    public ImportTaskVO createImportTask(MultipartFile file, CartImportDTO dto) {
        validateFile(file);

        String taskId = java.util.UUID.randomUUID().toString().replace("-", "");
        String userId = CartContextHolder.getUserId();
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();

        if (StringUtils.isBlank(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录，请先登录");
        }

        ImportTaskVO task = ImportTaskVO.builder()
                .taskId(taskId)
                .tenantId(tenantId)
                .userId(userId)
                .status(ImportTaskVO.STATUS_PENDING)
                .totalCount(0)
                .successCount(0)
                .failCount(0)
                .createTime(System.currentTimeMillis())
                .build();

        saveTask(task);

        byte[] fileData;
        try {
            fileData = file.getBytes();
        } catch (IOException e) {
            task.setStatus(ImportTaskVO.STATUS_FAILED);
            task.setErrorMessage("读取文件失败: " + e.getMessage());
            saveTask(task);
            throw new BusinessException(ResultCode.IMPORT_FILE_INVALID.getCode(), "读取文件失败: " + e.getMessage());
        }

        cartImportAsyncService.asyncProcessImport(taskId, tenantId, bizType, userId, fileData, dto);

        return task;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.IMPORT_FILE_EMPTY);
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ResultCode.IMPORT_FILE_TOO_LARGE);
        }
        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.toLowerCase().endsWith(".xlsx") && !filename.toLowerCase().endsWith(".xls"))) {
            throw new BusinessException(ResultCode.IMPORT_FILE_INVALID);
        }
    }

    public void saveTask(ImportTaskVO task) {
        String key = RedisKeyConstant.buildImportTaskKey(task.getTaskId());
        stringRedisTemplate.opsForValue().set(key, JsonUtil.toJson(task),
                java.time.Duration.ofHours(TASK_EXPIRE_HOURS));
    }

    public ImportTaskVO getTask(String taskId) {
        String key = RedisKeyConstant.buildImportTaskKey(taskId);
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isBlank(json)) {
            return null;
        }
        return JsonUtil.fromJson(json, ImportTaskVO.class);
    }

    public ImportTaskVO getImportTaskStatus(String taskId) {
        ImportTaskVO task = getTask(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.IMPORT_TASK_NOT_FOUND);
        }
        checkTaskOwnership(task);
        return task;
    }

    public byte[] downloadErrorReport(String taskId) {
        ImportTaskVO task = getTask(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.IMPORT_TASK_NOT_FOUND);
        }
        checkTaskOwnership(task);
        if (StringUtils.isBlank(task.getErrorReportUrl())) {
            throw new BusinessException(ResultCode.IMPORT_REPORT_NOT_READY);
        }

        List<ImportErrorRecord> records = getErrorRecords(taskId);
        if (records.isEmpty()) {
            throw new BusinessException(ResultCode.IMPORT_REPORT_NOT_READY);
        }

        List<ImportErrorReportVO> reportList = records.stream()
                .map(r -> ImportErrorReportVO.builder()
                        .rowNum(r.getRowNum())
                        .skuId(r.getSkuId())
                        .spuId(r.getSpuId())
                        .itemName(r.getItemName())
                        .errorMessage(r.getErrorMessage())
                        .rawData(r.getRawData())
                        .build())
                .collect(Collectors.toList());

        ExportParams params = new ExportParams("导入失败报告", "失败记录", ExcelType.XSSF);
        Workbook workbook = ExcelExportUtil.exportExcel(params,
                ImportErrorReportVO.class, reportList);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Download error report failed", e);
            throw new BusinessException("下载失败报告失败: " + e.getMessage());
        }
    }

    private void checkTaskOwnership(ImportTaskVO task) {
        String currentTenantId = CartContextHolder.getTenantId();
        String currentUserId = CartContextHolder.getUserId();
        if (StringUtils.isAnyBlank(currentTenantId, currentUserId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录，请先登录");
        }
        if (!currentTenantId.equals(task.getTenantId()) || !currentUserId.equals(task.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权访问该导入任务");
        }
    }

    public void saveErrorRecords(String taskId, List<ImportErrorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        String key = RedisKeyConstant.buildImportErrorKey(taskId);
        RList<String> list = redissonClient.getList(key);
        for (ImportErrorRecord record : records) {
            list.add(JsonUtil.toJson(record));
        }
        list.expire(java.time.Duration.ofHours(TASK_EXPIRE_HOURS));
    }

    public List<ImportErrorRecord> getErrorRecords(String taskId) {
        String key = RedisKeyConstant.buildImportErrorKey(taskId);
        RList<String> list = redissonClient.getList(key);
        if (list.isEmpty()) {
            return Collections.emptyList();
        }
        List<ImportErrorRecord> records = new ArrayList<>();
        for (String json : list.readAll()) {
            try {
                records.add(JsonUtil.fromJson(json, ImportErrorRecord.class));
            } catch (Exception e) {
                log.warn("Parse error record failed", e);
            }
        }
        return records;
    }

    public CartItem convertToCartItem(CartItemExcelVO vo, CartImportDTO dto) {
        Map<String, String> itemSpec = null;
        if (StringUtils.isNotBlank(vo.getSpecDesc())) {
            itemSpec = new HashMap<>();
            itemSpec.put("desc", vo.getSpecDesc());
        }

        Map<String, Object> extInfo = null;
        if (StringUtils.isNotBlank(vo.getExtInfo())) {
            try {
                extInfo = JsonUtil.fromJson(vo.getExtInfo(), Map.class);
            } catch (Exception e) {
                extInfo = new HashMap<>();
                extInfo.put("raw", vo.getExtInfo());
            }
        }

        return CartItem.builder()
                .skuId(vo.getSkuId())
                .spuId(vo.getSpuId())
                .categoryId(vo.getCategoryId())
                .shopId(vo.getShopId())
                .itemName(vo.getItemName())
                .itemImage(vo.getItemImage())
                .itemSpec(itemSpec)
                .unitPrice(vo.getUnitPrice())
                .originalPrice(vo.getOriginalPrice())
                .quantity(vo.getQuantity())
                .stock(vo.getStock())
                .selected(vo.getSelected() != null ? vo.getSelected() : true)
                .addSource(StringUtils.defaultIfBlank(dto.getAddSource(), "excel_import"))
                .remark(vo.getRemark())
                .sortWeight(vo.getSortWeight())
                .extInfo(extInfo)
                .build();
    }

    public byte[] exportCart() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new BusinessException(ResultCode.EXPORT_CART_EMPTY);
        }

        List<CartItemExcelVO> exportList = new ArrayList<>();
        for (CartItem item : cart.getItems()) {
            String specDesc = "";
            if (item.getItemSpec() != null && !item.getItemSpec().isEmpty()) {
                specDesc = JSON.toJSONString(item.getItemSpec());
            }
            String extInfoStr = "";
            if (item.getExtInfo() != null && !item.getExtInfo().isEmpty()) {
                extInfoStr = JSON.toJSONString(item.getExtInfo());
            }
            exportList.add(CartItemExcelVO.builder()
                    .skuId(item.getSkuId())
                    .spuId(item.getSpuId())
                    .itemName(item.getItemName())
                    .itemImage(item.getItemImage())
                    .specDesc(specDesc)
                    .categoryId(item.getCategoryId())
                    .shopId(item.getShopId())
                    .unitPrice(item.getUnitPrice())
                    .originalPrice(item.getOriginalPrice())
                    .quantity(item.getQuantity())
                    .stock(item.getStock())
                    .selected(item.getSelected())
                    .remark(item.getRemark())
                    .sortWeight(item.getSortWeight())
                    .addSource(item.getAddSource())
                    .extInfo(extInfoStr)
                    .build());
        }

        ExportParams params = new ExportParams("购物车商品明细", "商品列表", ExcelType.XSSF);
        Workbook workbook = ExcelExportUtil.exportExcel(params,
                CartItemExcelVO.class, exportList);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Export cart failed", e);
            throw new BusinessException(ResultCode.EXPORT_FAILED.getCode(),
                    "导出失败: " + e.getMessage());
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportErrorRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        private Integer rowNum;
        private String skuId;
        private String spuId;
        private String itemName;
        private String errorMessage;
        private String rawData;
    }

}

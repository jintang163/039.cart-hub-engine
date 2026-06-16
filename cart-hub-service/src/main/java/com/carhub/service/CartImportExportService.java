package com.carhub.service;

import cn.afterturn.easypoi.excel.ExcelExportUtil;
import cn.afterturn.easypoi.excel.ExcelImportUtil;
import cn.afterturn.easypoi.excel.entity.ExportParams;
import cn.afterturn.easypoi.excel.entity.ImportParams;
import cn.afterturn.easypoi.excel.entity.enmus.ExcelType;
import com.alibaba.fastjson.JSON;
import com.carhub.common.constant.CartConstant;
import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.common.context.CartContextHolder;
import com.carhub.common.exception.BusinessException;
import com.carhub.common.result.ResultCode;
import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.dto.CartImportDTO;
import com.carhub.domain.dto.ProductValidateDTO;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.domain.vo.CartItemExcelVO;
import com.carhub.domain.vo.ImportErrorReportVO;
import com.carhub.domain.vo.ImportTaskVO;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartImportExportService {

    private final CartRedisStorage cartRedisStorage;
    private final CartValidateService cartValidateService;
    private final CartStatisticsService cartStatisticsService;
    private final CartDbSyncService cartDbSyncService;
    private final MinioStorageService minioStorageService;
    private final CartHistoryService cartHistoryService;
    private final CartHubProperties cartHubProperties;
    private final RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final int TASK_EXPIRE_HOURS = 24;
    private static final int BATCH_VALIDATE_SIZE = 50;
    private static final int BATCH_ADD_SIZE = 100;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    public ImportTaskVO createImportTask(MultipartFile file, CartImportDTO dto) {
        validateFile(file);

        String taskId = UUID.randomUUID().toString().replace("-", "");
        String userId = CartContextHolder.getUserId();
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();

        ImportTaskVO task = ImportTaskVO.builder()
                .taskId(taskId)
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

        asyncProcessImport(taskId, tenantId, bizType, userId, fileData, dto);

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

    @Async
    public void asyncProcessImport(String taskId, String tenantId, String bizType, String userId, byte[] fileData, CartImportDTO dto) {
        ImportTaskVO task = getTask(taskId);
        if (task == null) {
            log.error("Import task not found: {}", taskId);
            return;
        }

        try {
            task.setStatus(ImportTaskVO.STATUS_PROCESSING);
            task.setStartTime(System.currentTimeMillis());
            saveTask(task);

            List<CartItemExcelVO> excelItems = parseExcel(fileData);
            if (excelItems == null || excelItems.isEmpty()) {
                task.setStatus(ImportTaskVO.STATUS_FAILED);
                task.setErrorMessage(ResultCode.IMPORT_NO_VALID_DATA.getMessage());
                task.setEndTime(System.currentTimeMillis());
                saveTask(task);
                return;
            }

            task.setTotalCount(excelItems.size());
            saveTask(task);

            List<ImportErrorRecord> errorRecords = new ArrayList<>();
            List<CartItem> validItems = new ArrayList<>();

            processImportData(excelItems, tenantId, bizType, dto, errorRecords, validItems);

            int successCount = 0;
            if (!validItems.isEmpty()) {
                successCount = addItemsToCart(tenantId, bizType, userId, validItems, dto, errorRecords);
            }

            task.setSuccessCount(successCount);
            task.setFailCount(errorRecords.size());
            task.setStatus(ImportTaskVO.STATUS_COMPLETED);
            task.setEndTime(System.currentTimeMillis());

            if (!errorRecords.isEmpty()) {
                saveErrorRecords(taskId, errorRecords);
                String reportUrl = generateErrorReport(taskId, tenantId, errorRecords);
                task.setErrorReportUrl(reportUrl);
            }

            saveTask(task);

            if (successCount > 0) {
                cartDbSyncService.markNeedSync(tenantId, bizType, userId);
                recordImportHistory(tenantId, bizType, userId, successCount, validItems);
            }

            log.info("Import task completed: taskId={}, total={}, success={}, fail={}",
                    taskId, task.getTotalCount(), successCount, errorRecords.size());

        } catch (Exception e) {
            log.error("Import task failed: taskId={}", taskId, e);
            task.setStatus(ImportTaskVO.STATUS_FAILED);
            task.setErrorMessage("系统异常: " + e.getMessage());
            task.setEndTime(System.currentTimeMillis());
            saveTask(task);
        }
    }

    private List<CartItemExcelVO> parseExcel(byte[] fileData) {
        ImportParams params = new ImportParams();
        params.setTitleRows(0);
        params.setHeadRows(1);
        try (InputStream is = new ByteArrayInputStream(fileData)) {
            return ExcelImportUtil.importExcel(is, CartItemExcelVO.class, params);
        } catch (Exception e) {
            log.error("Parse excel error", e);
            throw new BusinessException(ResultCode.IMPORT_FILE_INVALID.getCode(),
                    "Excel解析失败: " + e.getMessage());
        }
    }

    private void processImportData(List<CartItemExcelVO> excelItems, String tenantId, String bizType,
                               CartImportDTO dto,
                               List<ImportErrorRecord> errorRecords,
                               List<CartItem> validItems) {
        List<ProductValidateDTO> validateBatch = new ArrayList<>();
        Map<Integer, CartItemExcelVO> rowMap = new HashMap<>();
        Map<Integer, Integer> indexToRowMap = new HashMap<>();

        for (int i = 0; i < excelItems.size(); i++) {
            CartItemExcelVO vo = excelItems.get(i);
            int rowNum = i + 2;

            if (StringUtils.isBlank(vo.getSkuId())) {
                errorRecords.add(new ImportErrorRecord(rowNum, vo.getSkuId(), vo.getSpuId(),
                        vo.getItemName(), "SKU编码不能为空", JSON.toJSONString(vo)));
                continue;
            }

            if (vo.getQuantity() == null || vo.getQuantity() <= 0) {
                errorRecords.add(new ImportErrorRecord(rowNum, vo.getSkuId(), vo.getSpuId(),
                        vo.getItemName(), "商品数量不能为空或小于等于0", JSON.toJSONString(vo)));
                continue;
            }

            int maxQty = cartHubProperties.getLimit().getMaxItemQuantity();
            if (vo.getQuantity() > maxQty) {
                errorRecords.add(new ImportErrorRecord(rowNum, vo.getSkuId(), vo.getSpuId(),
                        vo.getItemName(), "商品数量超过上限(" + maxQty + ")", JSON.toJSONString(vo)));
                continue;
            }

            if (vo.getUnitPrice() == null) {
                errorRecords.add(new ImportErrorRecord(rowNum, vo.getSkuId(), vo.getSpuId(),
                        vo.getItemName(), "商品单价不能为空", JSON.toJSONString(vo)));
                continue;
            }

            if (Boolean.TRUE.equals(dto.getValidateProduct())) {
                ProductValidateDTO validateDTO = new ProductValidateDTO();
                validateDTO.setSkuId(vo.getSkuId());
                validateDTO.setSpuId(vo.getSpuId());
                validateDTO.setUnitPrice(vo.getUnitPrice());
                validateDTO.setQuantity(vo.getQuantity());
                validateBatch.add(validateDTO);
                int batchIndex = validateBatch.size() - 1;
                rowMap.put(batchIndex, vo);
                indexToRowMap.put(batchIndex, rowNum);

                if (validateBatch.size() >= BATCH_VALIDATE_SIZE) {
                    validateAndFilter(tenantId, bizType, validateBatch, rowMap, indexToRowMap, errorRecords, validItems, dto);
                    validateBatch.clear();
                    rowMap.clear();
                    indexToRowMap.clear();
                }
            } else {
                CartItem item = convertToCartItem(vo, dto);
                validItems.add(item);
            }
        }

        if (!validateBatch.isEmpty()) {
            validateAndFilter(tenantId, bizType, validateBatch, rowMap, indexToRowMap, errorRecords, validItems, dto);
        }
    }

    private void validateAndFilter(String tenantId, String bizType,
                                  List<ProductValidateDTO> validateBatch,
                                  Map<Integer, CartItemExcelVO> rowMap,
                                  Map<Integer, Integer> indexToRowMap,
                                  List<ImportErrorRecord> errorRecords,
                                  List<CartItem> validItems,
                                  CartImportDTO dto) {
        if (validateBatch.isEmpty()) {
            return;
        }

        List<CartValidateService.ProductValidateResult> results =
                cartValidateService.remoteValidate(tenantId, bizType, validateBatch);

        Map<String, CartValidateService.ProductValidateResult> resultMap = new HashMap<>();
        for (CartValidateService.ProductValidateResult r : results) {
            if (r != null && StringUtils.isNotBlank(r.getSkuId())) {
                resultMap.put(r.getSkuId(), r);
            }
        }

        for (int i = 0; i < validateBatch.size(); i++) {
            ProductValidateDTO validateDTO = validateBatch.get(i);
            CartItemExcelVO vo = rowMap.get(i);
            if (vo == null) continue;

            Integer rowNum = indexToRowMap.get(i);
            if (rowNum == null) rowNum = i + 2;

            CartValidateService.ProductValidateResult result = resultMap.get(validateDTO.getSkuId());
            if (result == null || Boolean.FALSE.equals(result.getValid())) {
                String errorMsg = result != null ? result.getErrorMessage() : "商品校验失败";
                errorRecords.add(new ImportErrorRecord(rowNum, vo.getSkuId(), vo.getSpuId(),
                        vo.getItemName(), errorMsg, JSON.toJSONString(vo)));
                continue;
            } else {
                CartItem item = convertToCartItem(vo, dto);
                if (StringUtils.isNotBlank(result.getItemName())) {
                    item.setItemName(result.getItemName());
                }
                if (StringUtils.isNotBlank(result.getItemImage())) {
                    item.setItemImage(result.getItemImage());
                }
                if (result.getCurrentPrice() != null) {
                    item.setUnitPrice(result.getCurrentPrice());
                }
                if (result.getOriginalPrice() != null) {
                    item.setOriginalPrice(result.getOriginalPrice());
                }
                if (result.getStock() != null) {
                    item.setStock(result.getStock());
                }
                if (result.getOnShelf() != null) {
                    item.setOnShelf(result.getOnShelf());
                }
                validItems.add(item);
            }
        }
    }

    private CartItem convertToCartItem(CartItemExcelVO vo, CartImportDTO dto) {
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

    private int addItemsToCart(String tenantId, String bizType, String userId,
                             List<CartItem> items, CartImportDTO dto,
                             List<ImportErrorRecord> errorRecords) {
        int currentSize = cartRedisStorage.getItemCount(tenantId, bizType, userId);
        int maxCartSize = cartHubProperties.getLimit().getMaxCartSize();
        int availableSlots = maxCartSize - currentSize;

        if (availableSlots <= 0) {
            for (int i = 0; i < items.size(); i++) {
                CartItem item = items.get(i);
                int rowNum = i + 2;
                errorRecords.add(new ImportErrorRecord(rowNum, item.getSkuId(), item.getSpuId(),
                        item.getItemName(),
                        "购物车商品数量已达上限(" + maxCartSize + ")",
                        JSON.toJSONString(item)));
            }
            return 0;
        }

        List<CartItem> itemsToAdd = items;
        if (items.size() > availableSlots) {
            itemsToAdd = items.subList(0, availableSlots);
            for (int i = availableSlots; i < items.size(); i++) {
                CartItem item = items.get(i);
                int rowNum = i + 2;
                errorRecords.add(new ImportErrorRecord(rowNum, item.getSkuId(), item.getSpuId(),
                        item.getItemName(),
                        "购物车商品数量已达上限(" + maxCartSize + ")",
                        JSON.toJSONString(item)));
            }
        }

        int totalAdded = 0;
        for (int i = 0; i < itemsToAdd.size(); i += BATCH_ADD_SIZE) {
            int end = Math.min(i + BATCH_ADD_SIZE, itemsToAdd.size());
            List<CartItem> batch = itemsToAdd.subList(i, end);
            int added = cartRedisStorage.batchAddItems(tenantId, bizType, userId, batch,
                    Boolean.TRUE.equals(dto.getOverwrite()));
            totalAdded += added;
        }

        return totalAdded;
    }

    private void recordImportHistory(String tenantId, String bizType, String userId,
                                  int successCount, List<CartItem> items) {
        BigDecimal totalAmount = BigDecimal.ZERO;
        int totalQty = 0;
        for (CartItem item : items) {
            if (item.getUnitPrice() != null && item.getQuantity() != null) {
                totalAmount = totalAmount.add(
                        item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            }
            totalQty += item.getQuantity() != null ? item.getQuantity() : 0;
            cartHistoryService.recordHistory(tenantId, bizType, userId,
                    CartConstant.ACTION_ADD, item.getSkuId(), null,
                    item.getQuantity(), null, item.getUnitPrice(), null,
                    "excel_import");
        }
        cartStatisticsService.recordAdd(tenantId, bizType, userId, totalQty, totalAmount);
    }

    private void saveTask(ImportTaskVO task) {
        String key = RedisKeyConstant.buildImportTaskKey(task.getTaskId());
        stringRedisTemplate.opsForValue().set(key, JsonUtil.toJson(task),
                Duration.ofHours(TASK_EXPIRE_HOURS));
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
        return task;
    }

    private void saveErrorRecords(String taskId, List<ImportErrorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        String key = RedisKeyConstant.buildImportErrorKey(taskId);
        RList<String> list = redissonClient.getList(key);
        for (ImportErrorRecord record : records) {
            list.add(JsonUtil.toJson(record));
        }
        list.expire(Duration.ofHours(TASK_EXPIRE_HOURS));
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

    private String generateErrorReport(String taskId, String tenantId,
                                   List<ImportErrorRecord> errorRecords) {
        List<ImportErrorReportVO> reportList = errorRecords.stream()
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
            byte[] data = out.toByteArray();
            String base64Data = java.util.Base64.getEncoder().encodeToString(data);
            return minioStorageService.saveLog(tenantId, "import_error", base64Data);
        } catch (Exception e) {
            log.error("Generate error report failed", e);
            throw new BusinessException("生成失败报告失败: " + e.getMessage());
        }
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

    public byte[] downloadErrorReport(String taskId) {
        ImportTaskVO task = getTask(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.IMPORT_TASK_NOT_FOUND);
        }
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

package com.carhub.service;

import cn.afterturn.easypoi.excel.ExcelExportUtil;
import cn.afterturn.easypoi.excel.ExcelImportUtil;
import cn.afterturn.easypoi.excel.entity.ExportParams;
import cn.afterturn.easypoi.excel.entity.ImportParams;
import cn.afterturn.easypoi.excel.entity.enmus.ExcelType;
import com.alibaba.fastjson.JSON;
import com.carhub.common.constant.CartConstant;
import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.common.exception.BusinessException;
import com.carhub.common.result.ResultCode;
import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.dto.CartImportDTO;
import com.carhub.domain.dto.ProductValidateDTO;
import com.carhub.domain.model.CartItem;
import com.carhub.domain.vo.ImportTaskVO;
import com.carhub.service.excel.CartItemExcelVO;
import com.carhub.service.excel.ImportErrorReportVO;
import com.carhub.storage.CartRedisStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Workbook;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartImportAsyncService {

    private final CartRedisStorage cartRedisStorage;
    private final CartValidateService cartValidateService;
    private final CartStatisticsService cartStatisticsService;
    private final CartDbSyncService cartDbSyncService;
    private final MinioStorageService minioStorageService;
    private final CartHistoryService cartHistoryService;
    private final CartHubProperties cartHubProperties;
    private final RedissonClient redissonClient;
    private final CartImportExportService cartImportExportService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final int BATCH_VALIDATE_SIZE = 50;
    private static final int BATCH_ADD_SIZE = 100;

    @Async
    public void asyncProcessImport(String taskId, String tenantId, String bizType, String userId, byte[] fileData, CartImportDTO dto) {
        ImportTaskVO task = cartImportExportService.getTask(taskId);
        if (task == null) {
            log.error("Import task not found: {}", taskId);
            return;
        }

        try {
            task.setStatus(ImportTaskVO.STATUS_PROCESSING);
            task.setStartTime(System.currentTimeMillis());
            cartImportExportService.saveTask(task);

            List<CartItemExcelVO> excelItems = parseExcel(fileData);
            if (excelItems == null || excelItems.isEmpty()) {
                task.setStatus(ImportTaskVO.STATUS_FAILED);
                task.setErrorMessage(ResultCode.IMPORT_NO_VALID_DATA.getMessage());
                task.setEndTime(System.currentTimeMillis());
                cartImportExportService.saveTask(task);
                return;
            }

            task.setTotalCount(excelItems.size());
            cartImportExportService.saveTask(task);

            List<CartImportExportService.ImportErrorRecord> errorRecords = new ArrayList<>();
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
                cartImportExportService.saveErrorRecords(taskId, errorRecords);
                String reportUrl = generateErrorReport(taskId, tenantId, errorRecords);
                task.setErrorReportUrl(reportUrl);
            }

            cartImportExportService.saveTask(task);

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
            cartImportExportService.saveTask(task);
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
                                   List<CartImportExportService.ImportErrorRecord> errorRecords,
                                   List<CartItem> validItems) {
        List<ProductValidateDTO> validateBatch = new ArrayList<>();
        Map<Integer, CartItemExcelVO> rowMap = new HashMap<>();
        Map<Integer, Integer> indexToRowMap = new HashMap<>();

        for (int i = 0; i < excelItems.size(); i++) {
            CartItemExcelVO vo = excelItems.get(i);
            int rowNum = i + 2;

            if (StringUtils.isBlank(vo.getSkuId())) {
                errorRecords.add(CartImportExportService.ImportErrorRecord.builder()
                        .rowNum(rowNum).skuId(vo.getSkuId()).spuId(vo.getSpuId())
                        .itemName(vo.getItemName()).errorMessage("SKU编码不能为空")
                        .rawData(JSON.toJSONString(vo)).build());
                continue;
            }

            if (vo.getQuantity() == null || vo.getQuantity() <= 0) {
                errorRecords.add(CartImportExportService.ImportErrorRecord.builder()
                        .rowNum(rowNum).skuId(vo.getSkuId()).spuId(vo.getSpuId())
                        .itemName(vo.getItemName()).errorMessage("商品数量不能为空或小于等于0")
                        .rawData(JSON.toJSONString(vo)).build());
                continue;
            }

            int maxQty = cartHubProperties.getLimit().getMaxItemQuantity();
            if (vo.getQuantity() > maxQty) {
                errorRecords.add(CartImportExportService.ImportErrorRecord.builder()
                        .rowNum(rowNum).skuId(vo.getSkuId()).spuId(vo.getSpuId())
                        .itemName(vo.getItemName()).errorMessage("商品数量超过上限(" + maxQty + ")")
                        .rawData(JSON.toJSONString(vo)).build());
                continue;
            }

            if (vo.getUnitPrice() == null) {
                errorRecords.add(CartImportExportService.ImportErrorRecord.builder()
                        .rowNum(rowNum).skuId(vo.getSkuId()).spuId(vo.getSpuId())
                        .itemName(vo.getItemName()).errorMessage("商品单价不能为空")
                        .rawData(JSON.toJSONString(vo)).build());
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
                CartItem item = cartImportExportService.convertToCartItem(vo, dto);
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
                                   List<CartImportExportService.ImportErrorRecord> errorRecords,
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
                errorRecords.add(CartImportExportService.ImportErrorRecord.builder()
                        .rowNum(rowNum).skuId(vo.getSkuId()).spuId(vo.getSpuId())
                        .itemName(vo.getItemName()).errorMessage(errorMsg)
                        .rawData(JSON.toJSONString(vo)).build());
            } else {
                CartItem item = cartImportExportService.convertToCartItem(vo, dto);
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

    private int addItemsToCart(String tenantId, String bizType, String userId,
                               List<CartItem> items, CartImportDTO dto,
                               List<CartImportExportService.ImportErrorRecord> errorRecords) {
        int currentSize = cartRedisStorage.getItemCount(tenantId, bizType, userId);
        int maxCartSize = cartHubProperties.getLimit().getMaxCartSize();
        int availableSlots = maxCartSize - currentSize;

        if (availableSlots <= 0) {
            for (CartItem item : items) {
                errorRecords.add(CartImportExportService.ImportErrorRecord.builder()
                        .rowNum(null).skuId(item.getSkuId()).spuId(item.getSpuId())
                        .itemName(item.getItemName())
                        .errorMessage("购物车商品数量已达上限(" + maxCartSize + ")")
                        .rawData(JSON.toJSONString(item)).build());
            }
            return 0;
        }

        List<CartItem> itemsToAdd;
        if (items.size() > availableSlots) {
            itemsToAdd = new ArrayList<>(items.subList(0, availableSlots));
            for (int i = availableSlots; i < items.size(); i++) {
                CartItem item = items.get(i);
                errorRecords.add(CartImportExportService.ImportErrorRecord.builder()
                        .rowNum(null).skuId(item.getSkuId()).spuId(item.getSpuId())
                        .itemName(item.getItemName())
                        .errorMessage("购物车商品数量已达上限(" + maxCartSize + ")")
                        .rawData(JSON.toJSONString(item)).build());
            }
        } else {
            itemsToAdd = items;
        }

        int totalAdded = 0;
        for (int batchStart = 0; batchStart < itemsToAdd.size(); batchStart += BATCH_ADD_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_ADD_SIZE, itemsToAdd.size());
            List<CartItem> batch = itemsToAdd.subList(batchStart, batchEnd);

            if (Boolean.TRUE.equals(dto.getOverwrite())) {
                int added = cartRedisStorage.batchAddItems(tenantId, bizType, userId, batch, true);
                totalAdded += added;
            } else {
                List<String> existSkus = cartRedisStorage.getItemsBySkus(tenantId, bizType, userId,
                        batch.stream().map(CartItem::getSkuId).collect(Collectors.toList()))
                        .stream().map(CartItem::getSkuId).collect(Collectors.toList());

                List<CartItem> newItems = new ArrayList<>();
                for (CartItem item : batch) {
                    if (existSkus.contains(item.getSkuId())) {
                        errorRecords.add(CartImportExportService.ImportErrorRecord.builder()
                                .rowNum(null).skuId(item.getSkuId()).spuId(item.getSpuId())
                                .itemName(item.getItemName())
                                .errorMessage("商品已在购物车中，未覆盖模式下跳过")
                                .rawData(JSON.toJSONString(item)).build());
                    } else {
                        newItems.add(item);
                    }
                }

                if (!newItems.isEmpty()) {
                    int added = cartRedisStorage.batchAddItems(tenantId, bizType, userId, newItems, false);
                    totalAdded += added;
                }
            }
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

    private String generateErrorReport(String taskId, String tenantId,
                                       List<CartImportExportService.ImportErrorRecord> errorRecords) {
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

}

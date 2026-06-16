package com.carhub.service.excel;

import cn.afterturn.easypoi.excel.annotation.Excel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportErrorReportVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Excel(name = "行号", orderNum = "1", width = 10)
    private Integer rowNum;

    @Excel(name = "SKU编码", orderNum = "2", width = 20)
    private String skuId;

    @Excel(name = "SPU编码", orderNum = "3", width = 20)
    private String spuId;

    @Excel(name = "商品名称", orderNum = "4", width = 30)
    private String itemName;

    @Excel(name = "失败原因", orderNum = "5", width = 50)
    private String errorMessage;

    @Excel(name = "原始数据", orderNum = "6", width = 40)
    private String rawData;

}

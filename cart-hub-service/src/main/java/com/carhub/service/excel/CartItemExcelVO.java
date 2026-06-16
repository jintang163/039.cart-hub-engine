package com.carhub.service.excel;

import cn.afterturn.easypoi.excel.annotation.Excel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItemExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Excel(name = "SKU编码", orderNum = "1", width = 20, needMerge = true)
    private String skuId;

    @Excel(name = "SPU编码", orderNum = "2", width = 20)
    private String spuId;

    @Excel(name = "商品名称", orderNum = "3", width = 30)
    private String itemName;

    @Excel(name = "商品图片", orderNum = "4", width = 30)
    private String itemImage;

    @Excel(name = "规格描述", orderNum = "5", width = 25)
    private String specDesc;

    @Excel(name = "分类ID", orderNum = "6", width = 15)
    private String categoryId;

    @Excel(name = "店铺ID", orderNum = "7", width = 15)
    private String shopId;

    @Excel(name = "单价(元)", orderNum = "8", width = 15, numFormat = "#.##")
    private BigDecimal unitPrice;

    @Excel(name = "原价(元)", orderNum = "9", width = 15, numFormat = "#.##")
    private BigDecimal originalPrice;

    @Excel(name = "数量", orderNum = "10", width = 10)
    private Integer quantity;

    @Excel(name = "库存", orderNum = "11", width = 10)
    private Integer stock;

    @Excel(name = "是否选中", orderNum = "12", width = 10, replace = {"是_true", "否_false"})
    private Boolean selected;

    @Excel(name = "备注", orderNum = "13", width = 30)
    private String remark;

    @Excel(name = "排序权重", orderNum = "14", width = 12)
    private Integer sortWeight;

    @Excel(name = "添加来源", orderNum = "15", width = 15)
    private String addSource;

    @Excel(name = "扩展信息", orderNum = "16", width = 30)
    private String extInfo;

}

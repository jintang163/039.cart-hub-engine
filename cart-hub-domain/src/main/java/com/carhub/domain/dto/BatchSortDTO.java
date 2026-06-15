package com.carhub.domain.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.io.Serializable;
import java.util.List;

@Data
public class BatchSortDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Valid
    @NotEmpty(message = "排序商品列表不能为空")
    private List<SortItem> sortItems;

    @Data
    public static class SortItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private String skuId;
        private Integer sortWeight;
    }

}

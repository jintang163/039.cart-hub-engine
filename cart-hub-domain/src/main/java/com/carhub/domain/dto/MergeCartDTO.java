package com.carhub.domain.dto;

import com.carhub.domain.model.CartItem;
import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.List;

@Data
public class MergeCartDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sourceUserId;

    @NotNull(message = "待合并商品列表不能为空")
    @Valid
    private List<CartItem> items;

    private Boolean overwrite = false;

}

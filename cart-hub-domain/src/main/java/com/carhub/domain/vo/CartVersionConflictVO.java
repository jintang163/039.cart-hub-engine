package com.carhub.domain.vo;

import com.carhub.domain.model.CartItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartVersionConflictVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long clientVersion;

    private Long serverVersion;

    private List<CartItem> serverItems;

    private List<CartItem> addedItems;

    private List<CartItem> removedItems;

    private List<CartItem> modifiedItems;

    private Long updateTime;

    private String message;

}

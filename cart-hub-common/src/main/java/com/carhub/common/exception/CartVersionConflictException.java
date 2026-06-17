package com.carhub.common.exception;

import com.carhub.common.result.ResultCode;
import com.carhub.domain.vo.CartVersionConflictVO;
import lombok.Getter;

@Getter
public class CartVersionConflictException extends BusinessException {

    private static final long serialVersionUID = 1L;

    private final CartVersionConflictVO conflictInfo;

    public CartVersionConflictException(CartVersionConflictVO conflictInfo) {
        super(ResultCode.CART_VERSION_CONFLICT.getCode(), ResultCode.CART_VERSION_CONFLICT.getMessage());
        this.conflictInfo = conflictInfo;
    }

    public CartVersionConflictException(String message, CartVersionConflictVO conflictInfo) {
        super(ResultCode.CART_VERSION_CONFLICT.getCode(), message);
        this.conflictInfo = conflictInfo;
    }

}

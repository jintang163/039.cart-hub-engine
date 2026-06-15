package com.carhub.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    ERROR(500, "系统异常"),
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),

    CART_EMPTY(1001, "购物车为空"),
    CART_ITEM_NOT_FOUND(1002, "购物车商品不存在"),
    CART_ITEM_INVALID(1003, "商品信息无效"),
    CART_PRICE_CHANGED(1004, "商品价格已变动"),
    CART_STOCK_NOT_ENOUGH(1005, "商品库存不足"),
    CART_PRODUCT_OFF_SHELF(1006, "商品已下架"),
    CART_QUANTITY_INVALID(1007, "商品数量无效"),
    CART_MERGE_FAILED(1008, "购物车合并失败"),
    CART_SHARE_EXPIRED(1009, "购物车分享已过期"),
    CART_SHARE_NOT_FOUND(1010, "购物车分享不存在"),

    BIZ_TYPE_NOT_FOUND(2001, "业务类型不存在"),
    TENANT_NOT_FOUND(2002, "租户不存在"),

    DISCOUNT_NOT_FOUND(3001, "优惠信息不存在"),
    DISCOUNT_NOT_APPLICABLE(3002, "优惠不适用"),

    REMARK_TOO_LONG(4001, "商品备注过长"),
    REMARK_CONTAIN_SENSITIVE_WORD(4002, "商品备注包含敏感词"),

    SORT_SKU_LIST_INVALID(5001, "排序商品列表无效"),
    SORT_SKU_NOT_IN_CART(5002, "排序商品不在购物车中");

    private final Integer code;
    private final String message;

}

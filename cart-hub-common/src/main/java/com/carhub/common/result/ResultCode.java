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
    SORT_SKU_NOT_IN_CART(5002, "排序商品不在购物车中"),

    CHECKOUT_TOKEN_INVALID(6001, "结算Token无效或已过期"),
    CHECKOUT_STOCK_LOCK_FAILED(6002, "库存预占失败"),
    CHECKOUT_STOCK_NOT_ENOUGH(6003, "商品库存不足"),
    CHECKOUT_CART_EMPTY(6004, "购物车中没有可结算商品"),
    CHECKOUT_HAS_INVALID_ITEM(6005, "购物车包含无效商品，请先处理"),
    CHECKOUT_PRICE_CHANGED(6006, "商品价格已变动，请重新确认"),
    CHECKOUT_ALREADY_CONFIRMED(6007, "结算已确认，请勿重复操作"),
    CHECKOUT_ALREADY_CANCELED(6008, "结算已取消"),
    CHECKOUT_EXPIRED(6009, "结算已过期，请重新结算"),

    FAVORITE_ITEM_NOT_FOUND(7001, "收藏商品不存在"),
    FAVORITE_ALREADY_EXISTS(7002, "商品已收藏"),
    FAVORITE_EMPTY(7003, "收藏夹为空"),
    FAVORITE_ADD_FAILED(7004, "添加收藏失败"),

    IMPORT_TASK_NOT_FOUND(8001, "导入任务不存在"),
    IMPORT_TASK_PROCESSING(8002, "导入任务正在处理中"),
    IMPORT_FILE_EMPTY(8003, "导入文件不能为空"),
    IMPORT_FILE_INVALID(8004, "导入文件格式不正确"),
    IMPORT_FILE_TOO_LARGE(8005, "导入文件过大"),
    IMPORT_NO_VALID_DATA(8006, "导入文件中没有有效的商品数据"),
    IMPORT_REPORT_NOT_READY(8007, "失败报告尚未生成"),
    EXPORT_CART_EMPTY(8008, "购物车为空，无法导出"),
    EXPORT_FAILED(8009, "导出失败"),

    CART_VERSION_CONFLICT(9001, "购物车版本冲突，多设备同时修改"),
    CART_VERSION_OUTDATED(9002, "本地购物车版本已过期，请刷新后重试");

    private final Integer code;
    private final String message;

}

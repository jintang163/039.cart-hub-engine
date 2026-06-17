package com.carhub.common.constant;

public class AnalyticsConstant {

    public static final String EVENT_ADD_TO_CART = "add_to_cart";
    public static final String EVENT_REMOVE_FROM_CART = "remove_from_cart";
    public static final String EVENT_UPDATE_QUANTITY = "update_quantity";
    public static final String EVENT_CLEAR_CART = "clear_cart";
    public static final String EVENT_CHECKOUT_CLICK = "checkout_click";
    public static final String EVENT_CHECKOUT_CREATE = "checkout_create";
    public static final String EVENT_CHECKOUT_CONFIRM = "checkout_confirm";
    public static final String EVENT_CHECKOUT_CANCEL = "checkout_cancel";
    public static final String EVENT_VIEW_CART = "view_cart";
    public static final String EVENT_SELECT_ITEM = "select_item";
    public static final String EVENT_DESELECT_ITEM = "deselect_item";
    public static final String EVENT_APPLY_COUPON = "apply_coupon";
    public static final String EVENT_REMOVE_COUPON = "remove_coupon";
    public static final String EVENT_PAGE_VIEW = "page_view";
    public static final String EVENT_CLICK = "click";

    public static final String SOURCE_WEB = "web";
    public static final String SOURCE_H5 = "h5";
    public static final String SOURCE_MINIAPP = "miniapp";
    public static final String SOURCE_APP = "app";

    public static final String DIMENSION_TENANT = "tenant";
    public static final String DIMENSION_BIZ_TYPE = "biz_type";
    public static final String DIMENSION_CATEGORY = "category";
    public static final String DIMENSION_PRODUCT = "product";
    public static final String DIMENSION_DATE = "date";
    public static final String DIMENSION_HOUR = "hour";
    public static final String DIMENSION_SOURCE = "source";

    private AnalyticsConstant() {
    }
}

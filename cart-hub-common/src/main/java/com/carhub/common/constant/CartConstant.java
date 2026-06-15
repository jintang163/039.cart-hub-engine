package com.carhub.common.constant;

public class CartConstant {

    public static final Integer DEFAULT_CART_MAX_SIZE = 200;

    public static final Integer DEFAULT_ITEM_MAX_QUANTITY = 999;

    public static final Integer DEFAULT_SHARE_EXPIRE_HOURS = 24;

    public static final Integer DEFAULT_SNAPSHOT_EXPIRE_DAYS = 30;

    public static final Integer DEFAULT_VALIDATE_CACHE_SECONDS = 300;

    public static final String DEFAULT_TENANT_ID = "default";

    public static final String BIZ_TYPE_ECOMMERCE = "ecommerce";
    public static final String BIZ_TYPE_FOOD = "food";
    public static final String BIZ_TYPE_COURSE = "course";

    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_BIZ_TYPE = "X-Biz-Type";
    public static final String HEADER_USER_ID = "X-User-Id";

    public static final String TOPIC_PRODUCT_CHANGE = "cart_hub_product_change";
    public static final String TOPIC_CART_SNAPSHOT = "cart_hub_cart_snapshot";
    public static final String TOPIC_CART_SYNC = "cart_hub_cart_sync";

    public static final String GROUP_CART_PRODUCT_CHANGE = "cart_product_change_group";
    public static final String GROUP_CART_SYNC = "cart_sync_group";

    public static final String MINIO_BUCKET_SNAPSHOT = "cart-snapshot";
    public static final String MINIO_BUCKET_LOG = "cart-log";

    public static final String SOURCE_WEB = "web";
    public static final String SOURCE_APP = "app";
    public static final String SOURCE_MINI = "mini";

    public static final String ACTION_ADD = "add";
    public static final String ACTION_UPDATE = "update";
    public static final String ACTION_DELETE = "delete";
    public static final String ACTION_CLEAR = "clear";
    public static final String ACTION_MERGE = "merge";

}

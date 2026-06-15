package com.carhub.common.constant;

public class RedisKeyConstant {

    private static final String CART_PREFIX = "cart:";

    public static final String CART_HASH_KEY = CART_PREFIX + "{%s}:{%s}:{%s}";

    public static final String CART_TOTAL_KEY = CART_PREFIX + "total:{%s}:{%s}:{%s}";

    public static final String CART_ITEM_LOCK = CART_PREFIX + "lock:{%s}:{%s}:{%s}:{%s}";

    public static final String CART_SHARE_KEY = CART_PREFIX + "share:{%s}";

    public static final String CART_SNAPSHOT_KEY = CART_PREFIX + "snapshot:{%s}:{%s}";

    public static final String CART_STAT_KEY = CART_PREFIX + "stat:{%s}:{%s}";

    public static final String CART_BIZ_CONFIG = CART_PREFIX + "biz:config:{%s}";

    public static final String CART_VALIDATE_CACHE = CART_PREFIX + "validate:{%s}:{%s}";

    public static final String CART_RECALC_LOCK = CART_PREFIX + "recalc:lock:{%s}";

    public static String buildCartKey(String tenantId, String bizType, String userId) {
        return String.format(CART_HASH_KEY, tenantId, bizType, userId);
    }

    public static String buildCartTotalKey(String tenantId, String bizType, String userId) {
        return String.format(CART_TOTAL_KEY, tenantId, bizType, userId);
    }

    public static String buildCartShareKey(String shareId) {
        return String.format(CART_SHARE_KEY, shareId);
    }

    public static String buildCartSnapshotKey(String tenantId, String snapshotId) {
        return String.format(CART_SNAPSHOT_KEY, tenantId, snapshotId);
    }

    public static String buildCartStatKey(String tenantId, String bizType) {
        return String.format(CART_STAT_KEY, tenantId, bizType);
    }

    public static String buildBizConfigKey(String bizType) {
        return String.format(CART_BIZ_CONFIG, bizType);
    }

    public static String buildValidateCacheKey(String bizType, String skuId) {
        return String.format(CART_VALIDATE_CACHE, bizType, skuId);
    }

}

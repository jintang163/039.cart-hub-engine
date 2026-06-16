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

    public static final String CART_META_KEY = CART_PREFIX + "meta:{%s}:{%s}:{%s}";

    public static final String CART_RECOMMEND_KEY = CART_PREFIX + "recommend:{%s}:{%s}:{%s}";

    public static final String CART_RECOMMEND_LOCK = CART_PREFIX + "recommend:lock:{%s}:{%s}";

    public static final String SKU_CO_OCCURRENCE_KEY = CART_PREFIX + "cooccurrence:{%s}:{%s}";

    public static final String CART_LAST_ACCESS_KEY = CART_PREFIX + "last_access:{%s}:{%s}:{%s}";

    public static final String CART_EXPIRE_REMIND_KEY = CART_PREFIX + "expire_remind:{%s}:{%s}:{%s}";

    public static final String CART_CLEANUP_LOCK_KEY = CART_PREFIX + "cleanup:lock";

    public static final String CART_CLEANUP_STAT_KEY = CART_PREFIX + "cleanup:stat:{%s}";

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

    public static String buildCartMetaKey(String tenantId, String bizType, String userId) {
        return String.format(CART_META_KEY, tenantId, bizType, userId);
    }

    public static String buildRecommendKey(String tenantId, String bizType, String userId) {
        return String.format(CART_RECOMMEND_KEY, tenantId, bizType, userId);
    }

    public static String buildRecommendLockKey(String tenantId, String bizType) {
        return String.format(CART_RECOMMEND_LOCK, tenantId, bizType);
    }

    public static final String CART_CHECKOUT_KEY = CART_PREFIX + "checkout:{%s}";

    public static final String CART_CHECKOUT_LOCK = CART_PREFIX + "checkout:lock:{%s}";

    public static final String CART_CHECKOUT_USER_KEY = CART_PREFIX + "checkout:user:{%s}:{%s}:{%s}";

    public static String buildCheckoutKey(String checkoutToken) {
        return String.format(CART_CHECKOUT_KEY, checkoutToken);
    }

    public static String buildCheckoutLockKey(String checkoutToken) {
        return String.format(CART_CHECKOUT_LOCK, checkoutToken);
    }

    public static String buildCheckoutUserKey(String tenantId, String bizType, String userId) {
        return String.format(CART_CHECKOUT_USER_KEY, tenantId, bizType, userId);
    }

    public static String buildSkuCoOccurrenceKey(String tenantId, String bizType) {
        return String.format(SKU_CO_OCCURRENCE_KEY, tenantId, bizType);
    }

    public static String buildLastAccessKey(String tenantId, String bizType, String userId) {
        return String.format(CART_LAST_ACCESS_KEY, tenantId, bizType, userId);
    }

    public static String buildExpireRemindKey(String tenantId, String bizType, String userId) {
        return String.format(CART_EXPIRE_REMIND_KEY, tenantId, bizType, userId);
    }

    public static String buildCleanupStatKey(String date) {
        return String.format(CART_CLEANUP_STAT_KEY, date);
    }

}

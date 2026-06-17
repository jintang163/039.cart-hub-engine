export declare class CartHubSDK {
    constructor(options: CartHubOptions);

    static create(options: CartHubOptions): CartHubSDK;
    static readonly VERSION: string;

    setUserId(userId: string | null): Promise<CartVO | null>;
    setToken(token: string | null): void;
    isLoggedIn(): boolean;

    getLocalCart(): LocalCart;
    addLocalItem(item: CartItemInput): LocalCart;
    removeLocalItem(skuId: string): LocalCart;
    updateLocalItem(skuId: string, updates: Partial<CartItemInput>): LocalCart;
    clearLocalCart(): LocalCart;

    addItem(item: CartItemInput, options?: VersionConflictOptions): Promise<CartVO>;
    updateItem(item: UpdateCartItemInput, options?: VersionConflictOptions): Promise<CartVO>;
    incrementQuantity(skuId: string, delta?: number, options?: VersionConflictOptions): Promise<CartVO>;
    removeItem(skuId: string, options?: VersionConflictOptions): Promise<CartVO>;
    batchRemove(skuIds: string[], options?: VersionConflictOptions): Promise<CartVO>;
    clearCart(options?: VersionConflictOptions): Promise<CartVO>;

    getCartVersion(): number;
    forceRefreshCart(): Promise<CartVO>;
    resolveConflict(choice: 'overwrite' | 'merge' | 'accept_server', conflictInfo?: CartVersionConflictVO): Promise<any>;

    getCart(validate?: boolean, checkInventory?: boolean): Promise<CartVO | (LocalCart & { local: true })>;
    getCartSimple(): Promise<CartVO | LocalCart>;
    getItemCount(): Promise<{ skuCount: number; totalQuantity: number }>;
    getCartSummary(): Promise<CartSummary & { local?: boolean }>;
    getExpireInfo(): Promise<CartExpireInfo>;

    subscribePriceDrop(skuId: string, targetPrice?: number): Promise<boolean>;
    unsubscribePriceDrop(skuId: string): Promise<boolean>;
    batchUnsubscribePriceDrop(skuIds?: string[]): Promise<boolean>;
    getPriceDropInfo(): Promise<PriceDropInfo>;

    mergeCart(options?: MergeCartOptions & VersionConflictOptions): Promise<CartVO>;

    createShare(options?: CreateShareOptions): Promise<ShareResult>;
    viewShare(shareId: string, password?: string): Promise<CartData>;
    acceptShare(shareId: string, password?: string): Promise<AcceptShareResult>;
    listMyShares(): Promise<ShareEntity[]>;
    cancelShare(shareId: string): Promise<boolean>;

    createSnapshot(options?: CreateSnapshotOptions): Promise<SnapshotResult>;
    getSnapshot(snapshotId: string): Promise<CartData>;
    restoreSnapshot(snapshotId: string): Promise<boolean>;
    listMySnapshots(): Promise<SnapshotEntity[]>;
    deleteSnapshot(snapshotId: string): Promise<boolean>;

    applyDiscount(options: ApplyDiscountOptions): Promise<CartData>;
    removeDiscount(discountId: string): Promise<CartData>;
    listDiscounts(): Promise<CartDiscount[]>;
    clearDiscounts(): Promise<CartData>;

    applyCouponCode(couponCode: string): Promise<CartVO>;
    applyCoupon(couponId: string): Promise<CartVO>;
    removeCoupon(): Promise<CartVO>;
    applyPromotion(promotionId: string): Promise<CartVO>;
    removePromotion(promotionId: string): Promise<CartVO>;
    recalculateDiscount(): Promise<CartVO>;
    listAvailableCoupons(totalAmount?: number): Promise<CouponVO[]>;
    listAvailablePromotions(totalAmount?: number): Promise<PromotionVO[]>;
    getDiscountResult(): Promise<DiscountResultVO>;
    getTieredDiscountProgress(totalAmount?: number): Promise<TieredDiscountProgressVO>;
    getRecommendations(options?: RecommendOptions): Promise<RecommendItemVO[]>;

    setItemRemark(skuId: string, remark: string, options?: VersionConflictOptions): Promise<boolean>;
    getItemRemark(skuId: string): Promise<string>;
    getAllItemRemarks(): Promise<Record<string, string>>;
    removeItemRemark(skuId: string, options?: VersionConflictOptions): Promise<boolean>;
    clearAllItemRemarks(options?: VersionConflictOptions): Promise<boolean>;

    batchSort(sortItems: SortItem[], options?: VersionConflictOptions): Promise<number>;
    reorderCartBySkus(orderedSkuIds: string[], options?: VersionConflictOptions): Promise<number>;

    on<K extends keyof CartHubEventMap>(event: K, callback: (data: CartHubEventMap[K]) => void): () => void;
    on(event: string, callback: (data: any) => void): () => void;
    off<K extends keyof CartHubEventMap>(event: K, callback: (data: CartHubEventMap[K]) => void): void;
    off(event: string, callback: (data: any) => void): void;

    track(eventName: string, properties?: Record<string, any>): void;
    trackAddToCart(item: CartItemInput): void;
    trackRemoveFromCart(skuId: string): void;
    trackUpdateQuantity(skuId: string, oldQty: number, newQty: number): void;
    trackCheckoutClick(position?: string): void;
    trackCheckoutCreate(checkoutData?: Record<string, any>): void;
    trackCheckoutConfirm(checkoutData?: Record<string, any>): void;
    trackCheckoutCancel(checkoutToken: string): void;
    trackApplyCoupon(couponId: string, couponCode?: string): void;
    trackRemoveCoupon(couponId: string): void;
    trackInventoryShortage(shortageItems: InventoryItemVO[]): void;

    checkInventory(items: InventoryItemDTO[]): Promise<InventoryCheckVO>;
    checkCartInventory(autoDeselect?: boolean): Promise<InventoryCheckVO>;
    getCartWithInventory(validate?: boolean, autoDeselect?: boolean): Promise<CartVO & {
        inventoryStatus?: InventoryStatus;
        inventoryError?: string;
    }>;
    applyInventoryStatusToCart(cart: any, inventoryResult: InventoryCheckVO): any;
    applyStockShortageStyles(containerSelector?: string, options?: {
        itemSelector?: string;
        checkboxSelector?: string;
    }): void;

    setSuperProperty(key: string, value: any): void;
    setSuperProperties(props: Record<string, any>): void;
    getSuperProperties(): Record<string, any>;
    clearSuperProperties(): void;
    flushEvents(): void;
    getPendingEventCount(): number;

    onBeforeRequest?: () => Record<string, string>;
}

export interface CartHubOptions {
    baseUrl: string;
    tenantId?: string;
    bizType?: string;
    userId?: string | null;
    token?: string | null;
    source?: 'web' | 'app' | 'mini' | string;
    timeout?: number;
    debug?: boolean;
    trackEnabled?: boolean;
    trackAuto?: boolean;
    trackBatchSize?: number;
    trackFlushInterval?: number;
    trackEndpoint?: string;
    trackClickSelector?: string;
    trackSuperProperties?: Record<string, any>;
    checkInventoryBeforeCheckout?: boolean;
    autoDeselectShortage?: boolean;
}

export interface CartItemInput {
    skuId: string;
    spuId?: string;
    categoryId?: string;
    shopId?: string;
    itemName?: string;
    itemImage?: string;
    itemSpec?: Record<string, string>;
    unitPrice: number;
    originalPrice?: number;
    quantity: number;
    stock?: number;
    selected?: boolean;
    addSource?: string;
    remark?: string;
    sortWeight?: number;
    extInfo?: Record<string, any>;
}

export interface UpdateCartItemInput {
    skuId: string;
    quantity?: number;
    unitPrice?: number;
    selected?: boolean;
    itemName?: string;
    itemImage?: string;
    itemSpec?: Record<string, string>;
    remark?: string;
    sortWeight?: number;
    extInfo?: Record<string, any>;
}

export interface LocalCart {
    items: CartItemInput[];
    version: number;
    local?: true;
    tenantId?: string;
    bizType?: string;
    anonymousId?: string;
}

export interface CartItemVO {
    skuId: string;
    spuId?: string;
    categoryId?: string;
    shopId?: string;
    itemName?: string;
    itemImage?: string;
    itemSpec?: Record<string, string>;
    unitPrice: string;
    originalPrice?: string;
    quantity: number;
    subtotal: string;
    discountAmount: string;
    payAmount: string;
    stock?: number;
    available?: boolean;
    availableQuantity?: number;
    onShelf: boolean;
    selected: boolean;
    priceChanged: boolean;
    oldPrice?: string;
    addTime?: number;
    addSource?: string;
    invalidMessage?: string;
    remark?: string;
    sortWeight?: number;
    priceDropSubscribed?: boolean;
    priceDropTargetPrice?: number;
    stockShortage?: boolean;
    shortageReason?: string;
    requestedQuantity?: number;
    stockStatusClass?: string;
    extInfo?: Record<string, any>;
}

export interface CartData {
    tenantId: string;
    bizType: string;
    userId: string;
    items: CartItemVO[];
    itemCount: number;
    totalQuantity: number;
    totalAmount: string;
    discountAmount: string;
    payAmount: string;
    hasPriceChanged: boolean;
    hasInvalidItem: boolean;
    discounts: CartDiscount[];
    version: number;
    updateTime: number;
}

export interface CartSummary {
    itemCount: number;
    totalQuantity: number;
    totalAmount: string;
    discountAmount: string;
    payAmount: string;
    hasPriceChanged: boolean;
    hasInvalidItem: boolean;
}

export interface MergeCartOptions {
    items?: CartItemInput[];
    sourceUserId?: string;
    overwrite?: boolean;
    anonymousLastAccessTime?: number;
    clearLocalAfterMerge?: boolean;
    _autoTrigger?: boolean;
}

export interface MergeResultPayload {
    cart: CartVO;
    autoTrigger: boolean;
    sourceUserId: string;
    itemsMerged: number;
    clearLocal: boolean;
}

export interface UserChangedEvent {
    oldUserId: string | null;
    newUserId: string | null;
    wasLoggedIn: boolean;
    nowLoggedIn: boolean;
}

export interface MergeFailedEvent {
    error: any;
    items: CartItemInput[];
}

export interface TrackEvent {
    eventType: string;
    eventId: string;
    timestamp: number;
    tenantId: string;
    bizType: string;
    userId?: string;
    anonymousId: string;
    sessionId: string;
    source: string;
    clientVersion: string;
    pageUrl?: string;
    pageTitle?: string;
    userAgent?: string;
    properties: Record<string, any>;
}

export type CartHubEventMap = {
    userChanged: UserChangedEvent;
    merged: MergeResultPayload;
    mergeFailed: MergeFailedEvent;
    localChanged: LocalCart;
    cartChanged: CartVO | LocalCart;
    versionConflict: CartVersionConflictVO;
    itemAdded: { item: CartItemInput; result: any; local?: boolean };
    itemUpdated: { item: any; result: any; local?: boolean };
    itemRemoved: { skuId: string; result: any; local?: boolean };
    itemsBatchRemoved: { skuIds: string[]; result: any; local?: boolean };
    quantityChanged: { skuId: string; delta: number; result: any; local?: boolean };
    cartCleared: any;
    cartLoaded: any;
    cartWithInventoryLoaded: any;
    inventoryStatusApplied: any;
    checkoutStockShortage: InventoryCheckVO;
    checkoutCreatedWithStockShortage: any;
    expireInfoLoaded: CartExpireInfo;
    priceDropSubscribed: { skuId: string; targetPrice?: number; result: any };
    priceDropUnsubscribed: { skuId: string; result: any };
    priceDropBatchUnsubscribed: { skuIds?: string[]; result: any };
    priceDropInfoLoaded: PriceDropInfo;
    trackEvent: TrackEvent;
    error: { url: string; error: any };
    success: { url: string; data: any };
};

export interface CreateShareOptions {
    title?: string;
    expireHours?: number;
    password?: string;
    shareType?: number;
}

export interface ShareResult {
    shareId: string;
    shareUrl: string;
    qrCodeUrl: string;
    expireTime: string;
    needPassword: boolean;
    itemCount?: number;
    totalQuantity?: number;
    totalAmount?: string;
    title?: string;
    viewCount?: number;
    acceptCount?: number;
}

export interface AcceptShareResult {
    success: boolean;
    mergedCount: number;
    mergedQuantity: number;
    mergedAmount: string;
    message: string;
    invalidItems: InvalidItem[];
}

export interface InvalidItem {
    skuId: string;
    itemName?: string;
    itemImage?: string;
    quantity: number;
    unitPrice: string;
    reason: string;
}

export interface ShareEntity {
    id: number;
    shareId: string;
    tenantId: string;
    bizType: string;
    ownerId: string;
    title?: string;
    itemCount: number;
    totalQuantity: number;
    totalAmount: string;
    shareType: number;
    viewCount: number;
    acceptCount: number;
    expireTime: string;
    createTime: string;
}

export interface CreateSnapshotOptions {
    snapshotName?: string;
    snapshotType?: 'manual' | 'auto' | 'share' | 'order';
    orderNo?: string;
}

export interface SnapshotResult {
    snapshotId: string;
    expireTime: string;
}

export interface SnapshotEntity {
    id: number;
    snapshotId: string;
    snapshotName?: string;
    snapshotType: string;
    itemCount: number;
    totalQuantity: number;
    totalAmount: string;
    orderNo?: string;
    storageType: number;
    expireTime?: string;
    createTime: string;
}

export interface ApplyDiscountOptions {
    discountId: string;
    discountType: string;
    discountName: string;
    discountCode?: string;
    discountAmount: number;
    applySkus?: string[];
    scope?: 'all' | 'item';
}

export interface CouponVO {
    couponId: string;
    couponName: string;
    couponType: string;
    promotionType?: string;
    thresholdAmount: string;
    discountAmount?: string;
    discountPercent?: number;
    maxDiscountAmount?: string;
    couponDesc?: string;
    startTime?: string;
    endTime?: string;
    available: boolean;
    unavailableReason?: string;
    applySkus?: string[];
    stackable?: boolean;
    priority?: number;
}

export interface GiftItemVO {
    skuId: string;
    itemName?: string;
    itemImage?: string;
    quantity?: number;
    unitPrice?: string;
}

export interface PromotionVO {
    promotionId: string;
    promotionName: string;
    promotionType: string;
    promotionDesc?: string;
    thresholdAmount: string;
    discountAmount?: string;
    discountPercent?: number;
    maxDiscountAmount?: string;
    startTime?: string;
    endTime?: string;
    available: boolean;
    unavailableReason?: string;
    applySkus?: string[];
    stackable?: boolean;
    priority?: number;
    gifts?: GiftItemVO[];
}

export interface DiscountDetailVO {
    discountId: string;
    discountType: string;
    discountName: string;
    discountCode?: string;
    discountAmount: string;
    promotionType?: string;
    promotionName?: string;
    promotionId?: string;
    applySkus?: string[];
    skuDiscountAmount?: Record<string, string>;
    thresholdAmount?: string;
    discountValue?: string;
    discountPercent?: number;
    maxDiscountAmount?: string;
    gifts?: GiftItemVO[];
    extInfo?: Record<string, any>;
}

export interface DiscountResultVO {
    totalAmount: string;
    discountAmount: string;
    payAmount: string;
    discounts?: CartDiscount[];
    discountDetails?: DiscountDetailVO[];
    gifts?: GiftItemVO[];
    errorMessage?: string;
    success: boolean;
}

export interface TieredDiscountProgressVO {
    totalAmount: string;
    currentDiscountAmount: string;
    currentTier?: TierInfo;
    nextTier?: TierInfo;
    allTiers: TierInfo[];
    progressTip: string;
}

export interface TierInfo {
    promotionId: string;
    promotionName: string;
    thresholdAmount: string;
    discountAmount: string;
    reached: boolean;
    gapAmount: string;
    progressPercent: number;
}

export interface CartDiscount {
    discountId: string;
    discountType: string;
    discountName: string;
    discountCode?: string;
    discountAmount: string;
    discountRule?: Record<string, any>;
    scope: string;
    applySkus?: string[];
    enable: boolean;
    errorMessage?: string;
    extInfo?: Record<string, any>;
    promotionType?: string;
    promotionId?: string;
    promotionName?: string;
    thresholdAmount?: string;
    discountValue?: string;
    discountPercent?: number;
    maxDiscountAmount?: string;
    gifts?: GiftItemVO[];
    details?: DiscountDetailVO[];
    skuDiscountAmount?: Record<string, string>;
    startTime?: string;
    endTime?: string;
    stackable?: boolean;
    priority?: number;
}

export interface CartVO {
    tenantId: string;
    bizType: string;
    userId: string;
    items: CartItemVO[];
    validItems: CartItemVO[];
    invalidItems: CartItemVO[];
    itemCount: number;
    validItemCount: number;
    totalQuantity: number;
    totalAmount: string;
    discountAmount: string;
    payAmount: string;
    hasPriceChanged: boolean;
    hasInvalidItem: boolean;
    discounts: CartDiscount[];
    validateEnabled: boolean;
    version: number;
    updateTime: number;
    selectedCouponId?: string;
    selectedPromotionIds?: string[];
    couponCode?: string;
    discountDetails?: DiscountDetailVO[];
    gifts?: GiftItemVO[];
    discountCalculated?: boolean;
    discountCalculateTime?: number;
    tieredDiscountProgress?: TieredDiscountProgressVO;
    lastAccessTime?: number;
    expireTime?: number;
    daysLeft?: number;
    hoursLeft?: number;
    isExpiring?: boolean;
    isExpired?: boolean;
    hasExpireReminded?: boolean;
    priceDropSubscriptionCount?: number;
    priceDropSubscriptions?: PriceDropSubscribeDetail[];
    priceDropEnabled?: boolean;
}

export interface CartExpireInfo {
    lastAccessTime?: number;
    expireTime?: number;
    retentionDays: number;
    remindBeforeDays: number;
    daysLeft: number;
    hoursLeft?: number;
    isExpiring: boolean;
    isExpired: boolean;
    hasReminded: boolean;
    itemCount?: number;
}

export interface PriceDropSubscribe {
    skuId: string;
    targetPrice?: number;
    createTs: number;
}

export interface PriceDropSubscribeDetail {
    skuId: string;
    targetPrice?: number;
    createTs?: number;
    itemName?: string;
    itemImage?: string;
    currentPrice?: number;
    priceGap?: number;
    gapPercent?: number;
    reachedTarget?: boolean;
    notified?: boolean;
}

export interface PriceDropInfo {
    subscriptions: PriceDropSubscribe[];
    subscriptionCount: number;
    details: PriceDropSubscribeDetail[];
    enabled: boolean;
    notifyEnabled: boolean;
    subscriptionExpireDays: number;
    minDropPercent?: number;
    minDropAmount?: number;
}

export interface RecommendOptions {
    currentSkus?: string[];
    topN?: number;
}

export interface SortItem {
    skuId: string;
    sortWeight?: number;
}

export interface RecommendItemVO {
    skuId: string;
    spuId?: string;
    itemName?: string;
    itemImage?: string;
    itemSpec?: Record<string, string>;
    unitPrice?: string;
    originalPrice?: string;
    score?: number;
    recommendReason?: string;
    coOccurrenceCount?: number;
    support?: number;
    confidence?: number;
    lift?: number;
    sourceSkus?: string[];
    extInfo?: Record<string, any>;
}

export default CartHubSDK;

export interface InventoryItemDTO {
    skuId: string;
    spuId?: string;
    quantity?: number;
    itemName?: string;
}

export interface InventoryItemVO {
    skuId: string;
    spuId?: string;
    itemName?: string;
    itemImage?: string;
    requestedQuantity?: number;
    availableQuantity?: number;
    stock?: number;
    available: boolean;
    shortageReason?: string;
    categoryId?: string;
    categoryName?: string;
    shopId?: string;
    unitPrice?: number;
}

export interface InventoryCheckVO {
    allAvailable: boolean;
    hasShortage: boolean;
    items: InventoryItemVO[];
    shortageItems: InventoryItemVO[];
}

export interface InventoryStatus {
    allAvailable: boolean;
    hasShortage: boolean;
    shortageCount: number;
    shortageSkuIds: string[];
    shortageItems: InventoryItemVO[];
    checkFailed?: boolean;
    errorMessage?: string;
}

export interface VersionConflictOptions {
    clientVersion?: number;
    forceOverwrite?: boolean;
}

export interface CartVersionConflictVO {
    clientVersion: number;
    serverVersion: number;
    serverItems: CartItemVO[];
    addedItems?: CartItemVO[];
    removedItems?: CartItemVO[];
    modifiedItems?: CartItemVO[];
    updateTime: number;
    message: string;
}


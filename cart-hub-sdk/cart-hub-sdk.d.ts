export declare class CartHubSDK {
    constructor(options: CartHubOptions);

    static create(options: CartHubOptions): CartHubSDK;
    static readonly VERSION: string;

    setUserId(userId: string | null): void;
    setToken(token: string | null): void;

    getLocalCart(): LocalCart;
    addLocalItem(item: CartItemInput): LocalCart;
    removeLocalItem(skuId: string): LocalCart;
    updateLocalItem(skuId: string, updates: Partial<CartItemInput>): LocalCart;
    clearLocalCart(): LocalCart;

    addItem(item: CartItemInput): Promise<boolean>;
    updateItem(item: UpdateCartItemInput): Promise<boolean>;
    incrementQuantity(skuId: string, delta?: number): Promise<number>;
    removeItem(skuId: string): Promise<boolean>;
    batchRemove(skuIds: string[]): Promise<number>;
    clearCart(): Promise<boolean>;

    getCart(validate?: boolean): Promise<CartVO>;
    getCartSimple(): Promise<CartVO>;
    getItemCount(): Promise<number>;
    getCartSummary(): Promise<CartSummary>;

    mergeCart(options?: MergeCartOptions): Promise<CartVO>;

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
    getRecommendations(options?: RecommendOptions): Promise<RecommendItemVO[]>;

    setItemRemark(skuId: string, remark: string): Promise<boolean>;
    getItemRemark(skuId: string): Promise<string>;
    getAllItemRemarks(): Promise<Record<string, string>>;
    removeItemRemark(skuId: string): Promise<boolean>;
    clearAllItemRemarks(): Promise<boolean>;

    batchSort(sortItems: SortItem[]): Promise<number>;
    reorderCartBySkus(orderedSkuIds: string[]): Promise<number>;

    on(event: string, callback: (data: any) => void): () => void;
    off(event: string, callback: (data: any) => void): void;

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
    onShelf: boolean;
    selected: boolean;
    priceChanged: boolean;
    oldPrice?: string;
    addTime?: number;
    addSource?: string;
    invalidMessage?: string;
    remark?: string;
    sortWeight?: number;
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
}

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

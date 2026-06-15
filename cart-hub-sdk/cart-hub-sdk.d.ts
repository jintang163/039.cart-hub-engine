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
    acceptShare(shareId: string, password?: string): Promise<boolean>;
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
    extInfo?: Record<string, any>;
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
    expireTime: string;
    needPassword: boolean;
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

export default CartHubSDK;

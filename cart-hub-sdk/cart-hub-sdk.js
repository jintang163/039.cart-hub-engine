/**
 * ============================================================
 * Cart-Hub SDK - 购物车引擎前端轻量SDK
 * 版本: 1.0.0
 * 功能: 封装购物车标准API，支持多业务隔离、本地缓存、登录合并
 * ============================================================
 */
(function (root, factory) {
    if (typeof define === 'function' && define.amd) {
        define([], factory);
    } else if (typeof module === 'object' && module.exports) {
        module.exports = factory();
    } else {
        root.CartHubSDK = factory();
    }
}(typeof self !== 'undefined' ? self : this, function () {
    'use strict';

    const VERSION = '1.0.0';
    const STORAGE_KEY_PREFIX = '__carthub__';
    const DEFAULT_HEADERS = { 'Content-Type': 'application/json' };

    function _guid() {
        return 'xxxxxxxxxxxx4xxxyxxxxxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
            const r = Math.random() * 16 | 0;
            const v = c === 'x' ? r : (r & 0x3 | 0x8);
            return v.toString(16);
        });
    }

    function _storageKey(tenantId, bizType) {
        return STORAGE_KEY_PREFIX + tenantId + '_' + bizType;
    }

    function _isFunction(obj) {
        return typeof obj === 'function';
    }

    class CartHubSDK {
        constructor(options) {
            if (!options || !options.baseUrl) {
                throw new Error('[CartHubSDK] baseUrl is required');
            }
            this.baseUrl = options.baseUrl.replace(/\/$/, '');
            this.tenantId = options.tenantId || 'default';
            this.bizType = options.bizType || 'ecommerce';
            this.userId = options.userId || null;
            this.token = options.token || null;
            this.source = options.source || 'web';
            this.timeout = options.timeout || 10000;
            this.debug = !!options.debug;
            this.options = {
                checkInventoryBeforeCheckout: options.checkInventoryBeforeCheckout !== false,
                autoDeselectShortage: options.autoDeselectShortage !== false
            };
            this.eventListeners = {};
            this._anonymousId = this._getAnonymousId();
            this._localCache = this._loadLocalCache();
            this._initTracking(options);
            this._log('initialized', {
                baseUrl: this.baseUrl,
                tenantId: this.tenantId,
                bizType: this.bizType,
                source: this.source,
                version: VERSION
            });
        }

        _log(...args) {
            if (this.debug) {
                console.log('[CartHubSDK]', ...args);
            }
        }

        _getAnonymousId() {
            const key = STORAGE_KEY_PREFIX + 'anonymous_id';
            let id = localStorage.getItem(key);
            if (!id) {
                id = 'anon_' + _guid();
                localStorage.setItem(key, id);
            }
            return id;
        }

        _getCurrentUserId() {
            return this.userId || this._anonymousId;
        }

        isLoggedIn() {
            return !!this.userId;
        }

        _getLocalMaxAddTime() {
            const items = this._localCache.items || [];
            if (!items.length) return Date.now();
            let max = 0;
            items.forEach(it => {
                const t = it.addTime || 0;
                if (t > max) max = t;
            });
            return max || Date.now();
        }

        setUserId(userId) {
            const oldUserId = this.userId;
            const wasLoggedIn = !!oldUserId;
            this.userId = userId;
            const nowLoggedIn = !!userId;
            this._log('setUserId', { oldUserId, newUserId: userId, wasLoggedIn, nowLoggedIn });
            this._emit('userChanged', { oldUserId, newUserId: userId, wasLoggedIn, nowLoggedIn });
            let mergePromise = Promise.resolve(null);
            if (!wasLoggedIn && nowLoggedIn && this._localCache.items && this._localCache.items.length > 0) {
                this._log('setUserId trigger anonymous merge, items:', this._localCache.items.length);
                const localSnapshot = JSON.parse(JSON.stringify(this._localCache.items));
                mergePromise = this.mergeCart({
                    items: localSnapshot,
                    clearLocalAfterMerge: true,
                    _autoTrigger: true
                }).catch(err => {
                    this._log('auto merge failed', err);
                    this._emit('mergeFailed', { error: err, items: localSnapshot });
                    return null;
                });
            }
            return mergePromise;
        }

        setToken(token) {
            this.token = token;
        }

        _buildHeaders() {
            const headers = Object.assign({}, DEFAULT_HEADERS);
            headers['X-Tenant-Id'] = this.tenantId;
            headers['X-Biz-Type'] = this.bizType;
            headers['X-User-Id'] = this._getCurrentUserId();
            headers['X-Source'] = this.source;
            headers['X-Client-Version'] = VERSION;
            if (this.token) {
                headers['Authorization'] = 'Bearer ' + this.token;
            }
            if (typeof this.onBeforeRequest === 'function') {
                Object.assign(headers, this.onBeforeRequest() || {});
            }
            return headers;
        }

        async _request(url, options = {}) {
            const fullUrl = this.baseUrl + url;
            const config = Object.assign({
                method: 'GET',
                headers: this._buildHeaders(),
                timeout: this.timeout,
                credentials: 'include'
            }, options);

            if (config.body && typeof config.body !== 'string') {
                config.body = JSON.stringify(config.body);
            }

            this._log('request', config.method, fullUrl, config.body);

            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), config.timeout);
            config.signal = controller.signal;

            try {
                const response = await fetch(fullUrl, config);
                clearTimeout(timeoutId);
                const text = await response.text();
                let result;
                try {
                    result = JSON.parse(text);
                } catch (e) {
                    result = { code: -1, message: 'Invalid JSON response', data: text };
                }
                this._log('response', result);
                if (result.code === 9001 || result.code === 9002) {
                    this._log('version conflict detected', result.data);
                    if (result.data && result.data.serverVersion != null) {
                        this._localCache.version = result.data.serverVersion;
                        this._saveLocalCache();
                    }
                    this._emit('versionConflict', result.data);
                    throw Object.assign(new Error(result.message || '购物车版本冲突'), result);
                }
                if (result.code !== 200 && result.code !== 0) {
                    this._emit('error', { url, error: result });
                    throw Object.assign(new Error(result.message || 'Request failed'), result);
                }
                this._emit('success', { url, data: result.data });
                if (result.data && result.data.version != null) {
                    this._updateServerVersion(result.data.version);
                    this._localCache.items = result.data.items || this._localCache.items;
                    this._saveLocalCache();
                }
                return result.data;
            } catch (error) {
                clearTimeout(timeoutId);
                if (error.name === 'AbortError') {
                    throw new Error('Request timeout');
                }
                throw error;
            }
        }

        _getCurrentVersion() {
            return this._localCache.version || 0;
        }

        _updateServerVersion(version) {
            if (version != null && (this._localCache.version == null || version > this._localCache.version)) {
                this._localCache.version = version;
                this._saveLocalCache();
                this._log('updated local version to', version);
            }
        }

        _loadLocalCache() {
            try {
                const raw = localStorage.getItem(_storageKey(this.tenantId, this.bizType));
                return raw ? JSON.parse(raw) : { items: [], version: 0 };
            } catch (e) {
                return { items: [], version: 0 };
            }
        }

        _saveLocalCache() {
            try {
                localStorage.setItem(
                    _storageKey(this.tenantId, this.bizType),
                    JSON.stringify(this._localCache)
                );
            } catch (e) {
                this._log('saveLocalCache error', e);
            }
        }

        getLocalCart() {
            return Object.assign({}, this._localCache);
        }

        addLocalItem(item) {
            if (!item || !item.skuId) {
                throw new Error('skuId is required');
            }
            const exist = this._localCache.items.find(i => i.skuId === item.skuId);
            if (exist) {
                exist.quantity = (exist.quantity || 0) + (item.quantity || 1);
            } else {
                this._localCache.items.push(Object.assign({
                    quantity: 1,
                    selected: true,
                    addTime: Date.now()
                }, item));
            }
            this._localCache.version++;
            this._saveLocalCache();
            this._emit('localChanged', this._localCache);
            return this.getLocalCart();
        }

        removeLocalItem(skuId) {
            this._localCache.items = this._localCache.items.filter(i => i.skuId !== skuId);
            this._localCache.version++;
            this._saveLocalCache();
            this._emit('localChanged', this._localCache);
            return this.getLocalCart();
        }

        updateLocalItem(skuId, updates) {
            const item = this._localCache.items.find(i => i.skuId === skuId);
            if (item) {
                Object.assign(item, updates);
                this._localCache.version++;
                this._saveLocalCache();
                this._emit('localChanged', this._localCache);
            }
            return this.getLocalCart();
        }

        clearLocalCart() {
            this._localCache = { items: [], version: 0 };
            this._saveLocalCache();
            this._emit('localChanged', this._localCache);
            return this.getLocalCart();
        }

        async addItem(item, options = {}) {
            if (!this.isLoggedIn()) {
                const localCart = this.addLocalItem(item);
                this._emit('itemAdded', { item, result: localCart, local: true });
                this._emit('cartChanged', localCart);
                return localCart;
            }
            const body = Object.assign({}, item, {
                clientVersion: options.clientVersion != null ? options.clientVersion : this._getCurrentVersion(),
                forceOverwrite: options.forceOverwrite || false,
                clientItems: options.clientItems || this._localCache.items
            });
            const result = await this._request('/api/cart/item', {
                method: 'POST',
                body: body
            });
            this._emit('itemAdded', { item, result });
            this._emit('cartChanged', result);
            return result;
        }

        async updateItem(item, options = {}) {
            if (!this.isLoggedIn()) {
                if (!item || !item.skuId) throw new Error('skuId is required');
                const localCart = this.updateLocalItem(item.skuId, item);
                this._emit('itemUpdated', { item, result: localCart, local: true });
                this._emit('cartChanged', localCart);
                return localCart;
            }
            const body = Object.assign({}, item, {
                clientVersion: options.clientVersion != null ? options.clientVersion : this._getCurrentVersion(),
                forceOverwrite: options.forceOverwrite || false,
                clientItems: options.clientItems || this._localCache.items
            });
            const result = await this._request('/api/cart/item', {
                method: 'PUT',
                body: body
            });
            this._emit('itemUpdated', { item, result });
            this._emit('cartChanged', result);
            return result;
        }

        async incrementQuantity(skuId, delta = 1, options = {}) {
            if (!this.isLoggedIn()) {
                const exist = this._localCache.items.find(i => i.skuId === skuId);
                if (!exist) throw new Error('SKU not found in local cart');
                const newQty = Math.max(1, (exist.quantity || 0) + delta);
                const localCart = this.updateLocalItem(skuId, { quantity: newQty });
                this._emit('quantityChanged', { skuId, delta, result: localCart, local: true });
                this._emit('cartChanged', localCart);
                return localCart;
            }
            const qs = new URLSearchParams({
                skuId,
                delta: String(delta),
                clientVersion: String(options.clientVersion != null ? options.clientVersion : this._getCurrentVersion()),
                forceOverwrite: String(options.forceOverwrite || false)
            });
            const clientItems = options.clientItems || this._localCache.items;
            const result = await this._request('/api/cart/item/quantity?' + qs.toString(), {
                method: 'PATCH',
                body: clientItems
            });
            this._emit('quantityChanged', { skuId, delta, result });
            this._emit('cartChanged', result);
            return result;
        }

        async removeItem(skuId, options = {}) {
            if (!this.isLoggedIn()) {
                const localCart = this.removeLocalItem(skuId);
                this._emit('itemRemoved', { skuId, result: localCart, local: true });
                this._emit('cartChanged', localCart);
                return localCart;
            }
            const qs = new URLSearchParams({
                skuId,
                clientVersion: String(options.clientVersion != null ? options.clientVersion : this._getCurrentVersion()),
                forceOverwrite: String(options.forceOverwrite || false)
            });
            const clientItems = options.clientItems || this._localCache.items;
            const result = await this._request('/api/cart/item?' + qs.toString(), {
                method: 'DELETE',
                body: clientItems
            });
            this._emit('itemRemoved', { skuId, result });
            this._emit('cartChanged', result);
            return result;
        }

        async batchRemove(skuIds, options = {}) {
            if (!this.isLoggedIn()) {
                let localCart = this.getLocalCart();
                (skuIds || []).forEach(id => {
                    localCart = this.removeLocalItem(id);
                });
                this._emit('itemsBatchRemoved', { skuIds, result: localCart, local: true });
                this._emit('cartChanged', localCart);
                return localCart;
            }
            const body = {
                skuIds,
                clientVersion: options.clientVersion != null ? options.clientVersion : this._getCurrentVersion(),
                forceOverwrite: options.forceOverwrite || false,
                clientItems: options.clientItems || this._localCache.items
            };
            const result = await this._request('/api/cart/items', {
                method: 'DELETE',
                body: body
            });
            this._emit('itemsBatchRemoved', { skuIds, result });
            this._emit('cartChanged', result);
            return result;
        }

        async clearCart(options = {}) {
            if (!this.isLoggedIn()) {
                const localCart = this.clearLocalCart();
                this._emit('cartCleared', { ...localCart, local: true });
                this._emit('cartChanged', localCart);
                return localCart;
            }
            const qs = new URLSearchParams({
                clientVersion: String(options.clientVersion != null ? options.clientVersion : this._getCurrentVersion()),
                forceOverwrite: String(options.forceOverwrite || false)
            });
            const clientItems = options.clientItems || this._localCache.items;
            const result = await this._request('/api/cart/clear?' + qs.toString(), {
                method: 'DELETE',
                body: clientItems
            });
            this._emit('cartCleared', result);
            this._emit('cartChanged', result);
            return result;
        }

        async getCart(validate = true, checkInventory = true) {
            if (!this.isLoggedIn()) {
                const localCart = this.getLocalCart();
                this._emit('cartLoaded', { ...localCart, local: true });
                return { ...localCart, local: true };
            }
            const qs = new URLSearchParams({ validate: String(validate) });
            const cart = await this._request('/api/cart?' + qs.toString());

            if (cart && cart.version != null) {
                this._updateServerVersion(cart.version);
            }

            if (checkInventory && cart && cart.items && cart.items.length > 0) {
                try {
                    const inventory = await this.checkCartInventory(true);
                    const cartWithInventory = this.applyInventoryStatusToCart(cart, inventory);
                    this._emit('cartWithInventoryLoaded', cartWithInventory);
                    this._emit('cartLoaded', cartWithInventory);
                    return cartWithInventory;
                } catch (e) {
                    this._log('Auto inventory check failed when loading cart', e);
                    this._emit('cartLoaded', cart);
                    return {
                        ...cart,
                        inventoryStatus: {
                            allAvailable: false,
                            hasShortage: false,
                            checkFailed: true,
                            errorMessage: e.message || '库存校验失败'
                        }
                    };
                }
            }

            this._emit('cartLoaded', cart);
            return cart;
        }

        async getCartSimple() {
            if (!this.isLoggedIn()) {
                return this.getLocalCart();
            }
            const result = await this._request('/api/cart/simple');
            if (result && result.version != null) {
                this._updateServerVersion(result.version);
            }
            this._emit('cartLoaded', result);
            return result;
        }

        async getItemCount() {
            if (!this.isLoggedIn()) {
                const items = this._localCache.items || [];
                let totalQty = 0;
                items.forEach(i => { totalQty += (i.quantity || 0); });
                return { skuCount: items.length, totalQuantity: totalQty };
            }
            return this._request('/api/cart/count');
        }

        async getCartSummary() {
            if (!this.isLoggedIn()) {
                const items = this._localCache.items || [];
                let totalQty = 0;
                let totalAmount = 0;
                items.forEach(i => {
                    const q = i.quantity || 0;
                    totalQty += q;
                    totalAmount += (i.unitPrice || 0) * q;
                });
                return {
                    skuCount: items.length,
                    totalQuantity: totalQty,
                    totalAmount: Number(totalAmount.toFixed(2)),
                    local: true
                };
            }
            return this._request('/api/cart/summary');
        }

        async getExpireInfo() {
            const result = await this._request('/api/cart/expire-info');
            this._emit('expireInfoLoaded', result);
            return result;
        }

        async subscribePriceDrop(skuId, targetPrice) {
            if (!skuId) throw new Error('skuId 不能为空');
            const qs = new URLSearchParams();
            qs.set('skuId', skuId);
            if (targetPrice != null) qs.set('targetPrice', String(targetPrice));
            const result = await this._request('/api/cart/price-drop/subscribe?' + qs.toString(), {
                method: 'POST'
            });
            this._emit('priceDropSubscribed', { skuId, targetPrice, result });
            return result;
        }

        async unsubscribePriceDrop(skuId) {
            if (!skuId) throw new Error('skuId 不能为空');
            const qs = new URLSearchParams();
            qs.set('skuId', skuId);
            const result = await this._request('/api/cart/price-drop/unsubscribe?' + qs.toString(), {
                method: 'DELETE'
            });
            this._emit('priceDropUnsubscribed', { skuId, result });
            return result;
        }

        async batchUnsubscribePriceDrop(skuIds) {
            const result = await this._request('/api/cart/price-drop/unsubscribe/batch', {
                method: 'DELETE',
                body: skuIds || []
            });
            this._emit('priceDropBatchUnsubscribed', { skuIds, result });
            return result;
        }

        async getPriceDropInfo() {
            const result = await this._request('/api/cart/price-drop/info');
            this._emit('priceDropInfoLoaded', result);
            return result;
        }

        async mergeCart(options = {}) {
            const autoTrigger = !!options._autoTrigger;
            const clearLocal = options.clearLocalAfterMerge !== false;
            const sourceItems = options.items && options.items.length
                ? options.items
                : this._localCache.items;
            const body = {
                items: sourceItems,
                sourceUserId: options.sourceUserId || this._anonymousId,
                overwrite: !!options.overwrite,
                anonymousLastAccessTime: options.anonymousLastAccessTime || this._getLocalMaxAddTime(),
                clientVersion: options.clientVersion != null ? options.clientVersion : this._getCurrentVersion(),
                forceOverwrite: options.forceOverwrite || false,
                clientItems: options.clientItems || this._localCache.items
            };
            const result = await this._request('/api/cart/merge', {
                method: 'POST',
                body
            });
            if (clearLocal) {
                this.clearLocalCart();
            }
            this._emit('merged', {
                cart: result,
                autoTrigger,
                sourceUserId: body.sourceUserId,
                itemsMerged: sourceItems.length,
                clearLocal
            });
            this._emit('cartChanged', result);
            return result;
        }

        getCartVersion() {
            return this._getCurrentVersion();
        }

        async forceRefreshCart() {
            return this.getCart(true, false);
        }

        async resolveConflict(choice, conflictInfo, retryFn) {
            if (choice === 'overwrite') {
                this._log('resolving conflict by forcing overwrite');
                if (typeof retryFn === 'function') {
                    this._log('retrying original operation with forceOverwrite=true');
                    try {
                        return await retryFn({ forceOverwrite: true });
                    } catch (e) {
                        if (e.code === 9001 || e.code === 9002) {
                            this._log('conflict still exists after overwrite, refreshing cart');
                            return this.forceRefreshCart();
                        }
                        throw e;
                    }
                }
                return { choice: 'overwrite', forceOverwrite: true };
            } else if (choice === 'merge') {
                this._log('resolving conflict by merging local items');
                const serverItems = conflictInfo && conflictInfo.serverItems ? conflictInfo.serverItems : [];
                const localItems = this._localCache.items || [];
                const mergedMap = {};
                serverItems.forEach(item => { if (item && item.skuId) mergedMap[item.skuId] = Object.assign({}, item); });
                localItems.forEach(item => {
                    if (item && item.skuId) {
                        if (mergedMap[item.skuId]) {
                            const existing = mergedMap[item.skuId];
                            existing.quantity = (existing.quantity || 0) + (item.quantity || 0);
                        } else {
                            mergedMap[item.skuId] = Object.assign({}, item);
                        }
                    }
                });
                const mergedItems = Object.values(mergedMap);
                const mergeResult = await this.mergeCart({
                    items: mergedItems,
                    clearLocalAfterMerge: true,
                    overwrite: true,
                    forceOverwrite: true
                });
                if (typeof retryFn === 'function') {
                    this._log('retrying original operation after merge');
                    try {
                        return await retryFn({ forceOverwrite: true });
                    } catch (e) {
                        this._log('retry failed after merge', e.message);
                        return mergeResult;
                    }
                }
                return mergeResult;
            } else if (choice === 'accept_server') {
                this._log('resolving conflict by accepting server version');
                if (conflictInfo && conflictInfo.serverVersion != null) {
                    this._updateServerVersion(conflictInfo.serverVersion);
                }
                return this.forceRefreshCart();
            }
            throw new Error('Unknown conflict resolution choice: ' + choice);
        }

        async createShare(options = {}) {
            const qs = new URLSearchParams();
            if (options.title) qs.set('title', options.title);
            if (options.expireHours) qs.set('expireHours', String(options.expireHours));
            if (options.password) qs.set('password', options.password);
            if (options.shareType) qs.set('shareType', String(options.shareType));
            return this._request('/api/cart/share?' + qs.toString(), {
                method: 'POST'
            });
        }

        async viewShare(shareId, password) {
            const qs = new URLSearchParams();
            if (password) qs.set('password', password);
            return this._request('/api/cart/share/view/' + shareId + '?' + qs.toString());
        }

        async acceptShare(shareId, password) {
            const qs = new URLSearchParams();
            if (password) qs.set('password', password);
            return this._request('/api/cart/share/accept/' + shareId + '?' + qs.toString(), {
                method: 'POST'
            });
        }

        async listMyShares() {
            return this._request('/api/cart/share/list');
        }

        async cancelShare(shareId) {
            return this._request('/api/cart/share/' + shareId, { method: 'DELETE' });
        }

        async createSnapshot(options = {}) {
            const result = await this._request('/api/cart/snapshot', {
                method: 'POST',
                body: {
                    snapshotName: options.snapshotName || null
                }
            });
            this._emit('snapshotCreated', result);
            return result;
        }

        async getSnapshot(snapshotId) {
            if (!snapshotId) throw new Error('snapshotId is required');
            return this._request('/api/cart/snapshot/' + snapshotId);
        }

        async restoreSnapshot(snapshotId, options = {}) {
            if (!snapshotId) throw new Error('snapshotId is required');
            const result = await this._request('/api/cart/snapshot/restore', {
                method: 'POST',
                body: {
                    snapshotId,
                    mergeCurrent: options.mergeCurrent || false,
                    clientVersion: options.clientVersion != null ? options.clientVersion : this._getCurrentVersion(),
                    forceOverwrite: options.forceOverwrite || false
                }
            });
            if (result && result.version != null) {
                this._updateServerVersion(result.version);
                this._localCache.items = result.items || this._localCache.items;
                this._saveLocalCache();
            }
            this._emit('snapshotRestored', { snapshotId, options });
            this._emit('cartChanged', result);
            return result;
        }

        async listMySnapshots(limit) {
            const qs = new URLSearchParams();
            if (limit != null) qs.set('limit', String(limit));
            return this._request('/api/cart/snapshot/list?' + qs.toString());
        }

        async deleteSnapshot(snapshotId) {
            if (!snapshotId) throw new Error('snapshotId is required');
            const result = await this._request('/api/cart/snapshot/' + snapshotId, { method: 'DELETE' });
            this._emit('snapshotDeleted', { snapshotId });
            return result;
        }

        async applyDiscount(options) {
            const qs = new URLSearchParams();
            Object.entries(options).forEach(([k, v]) => {
                if (Array.isArray(v)) {
                    v.forEach(item => qs.append(k, item));
                } else if (v !== undefined && v !== null) {
                    qs.set(k, String(v));
                }
            });
            return this._request('/api/cart/discount?' + qs.toString(), { method: 'POST' });
        }

        async removeDiscount(discountId) {
            return this._request('/api/cart/discount/' + discountId, { method: 'DELETE' });
        }

        async listDiscounts() {
            return this._request('/api/cart/discount/list');
        }

        async clearDiscounts() {
            return this._request('/api/cart/discount/clear', { method: 'DELETE' });
        }

        async applyCouponCode(couponCode) {
            return this._request('/api/cart/coupon/apply-code', {
                method: 'POST',
                body: { couponCode }
            });
        }

        async applyCoupon(couponId) {
            return this._request('/api/cart/coupon/apply/' + couponId, { method: 'POST' });
        }

        async removeCoupon() {
            return this._request('/api/cart/coupon/remove', { method: 'DELETE' });
        }

        async applyPromotion(promotionId) {
            return this._request('/api/cart/promotion/apply/' + promotionId, { method: 'POST' });
        }

        async removePromotion(promotionId) {
            return this._request('/api/cart/promotion/remove/' + promotionId, { method: 'DELETE' });
        }

        async recalculateDiscount() {
            return this._request('/api/cart/discount/recalculate', { method: 'POST' });
        }

        async listAvailableCoupons(totalAmount) {
            const qs = new URLSearchParams();
            if (totalAmount !== undefined && totalAmount !== null) {
                qs.set('totalAmount', String(totalAmount));
            }
            return this._request('/api/cart/coupon/available?' + qs.toString());
        }

        async listAvailablePromotions(totalAmount) {
            const qs = new URLSearchParams();
            if (totalAmount !== undefined && totalAmount !== null) {
                qs.set('totalAmount', String(totalAmount));
            }
            return this._request('/api/cart/promotion/available?' + qs.toString());
        }

        async getDiscountResult() {
            return this._request('/api/cart/discount/result');
        }

        async getTieredDiscountProgress(totalAmount) {
            const qs = new URLSearchParams();
            if (totalAmount !== undefined && totalAmount !== null) {
                qs.set('totalAmount', String(totalAmount));
            }
            return this._request('/api/cart/discount/tiered-progress?' + qs.toString());
        }

        async getRecommendations(options = {}) {
            const qs = new URLSearchParams();
            if (options.currentSkus && options.currentSkus.length > 0) {
                options.currentSkus.forEach(sku => qs.append('currentSkus', sku));
            }
            if (options.topN) {
                qs.set('topN', String(options.topN));
            }
            return this._request('/api/cart/recommend?' + qs.toString());
        }

        async setItemRemark(skuId, remark, options = {}) {
            const result = await this._request('/api/cart/item/remark', {
                method: 'PUT',
                body: {
                    skuId,
                    remark,
                    clientVersion: options.clientVersion != null ? options.clientVersion : this._getCurrentVersion(),
                    forceOverwrite: options.forceOverwrite || false,
                    clientItems: options.clientItems || this._localCache.items
                }
            });
            this._emit('remarkChanged', { skuId, remark });
            this._emit('cartChanged', result);
            return result;
        }

        async getItemRemark(skuId) {
            const qs = new URLSearchParams({ skuId });
            return this._request('/api/cart/item/remark?' + qs.toString());
        }

        async getAllItemRemarks() {
            return this._request('/api/cart/items/remarks');
        }

        async removeItemRemark(skuId, options = {}) {
            const qs = new URLSearchParams({
                skuId,
                clientVersion: String(options.clientVersion != null ? options.clientVersion : this._getCurrentVersion()),
                forceOverwrite: String(options.forceOverwrite || false)
            });
            const clientItems = options.clientItems || this._localCache.items;
            const result = await this._request('/api/cart/item/remark?' + qs.toString(), {
                method: 'DELETE',
                body: clientItems
            });
            this._emit('remarkChanged', { skuId, remark: null });
            this._emit('cartChanged', result);
            return result;
        }

        async clearAllItemRemarks(options = {}) {
            const qs = new URLSearchParams({
                clientVersion: String(options.clientVersion != null ? options.clientVersion : this._getCurrentVersion()),
                forceOverwrite: String(options.forceOverwrite || false)
            });
            const clientItems = options.clientItems || this._localCache.items;
            const result = await this._request('/api/cart/items/remarks?' + qs.toString(), {
                method: 'DELETE',
                body: clientItems
            });
            this._emit('remarkChanged', { skuId: null, remark: null, allCleared: true });
            this._emit('cartChanged', result);
            return result;
        }

        async batchSort(sortItems, options = {}) {
            if (!Array.isArray(sortItems)) {
                throw new Error('sortItems must be an array of {skuId, sortWeight}');
            }
            const items = sortItems.map((it, idx) => ({
                skuId: it.skuId,
                sortWeight: it.sortWeight != null ? it.sortWeight : idx
            }));
            const result = await this._request('/api/cart/items/sort', {
                method: 'PUT',
                body: {
                    sortItems: items,
                    clientVersion: options.clientVersion != null ? options.clientVersion : this._getCurrentVersion(),
                    forceOverwrite: options.forceOverwrite || false,
                    clientItems: options.clientItems || this._localCache.items
                }
            });
            this._emit('sortChanged', items);
            this._emit('cartChanged', result);
            return result;
        }

        async reorderCartBySkus(orderedSkuIds, options = {}) {
            if (!Array.isArray(orderedSkuIds)) {
                throw new Error('orderedSkuIds must be an array');
            }
            const sortItems = orderedSkuIds.map((skuId, idx) => ({
                skuId,
                sortWeight: (idx + 1) * 10
            }));
            return this.batchSort(sortItems, options);
        }

        async checkInventory(items) {
            if (!items || !items.length) {
                return { allAvailable: true, hasShortage: false, items: [], shortageItems: [] };
            }
            const result = await this._request('/api/inventory/check', {
                method: 'POST',
                body: { items: items }
            });
            if (result && result.hasShortage && result.shortageItems) {
                this._trackInventoryShortage(result.shortageItems);
            }
            return result;
        }

        async checkCartInventory(autoDeselect = false) {
            if (!this.isLoggedIn()) {
                return { allAvailable: true, hasShortage: false, items: [], shortageItems: [] };
            }
            const result = await this._request('/api/inventory/check-cart?autoDeselect=' + autoDeselect);
            if (result && result.hasShortage && result.shortageItems) {
                this._trackInventoryShortage(result.shortageItems);
            }
            return result;
        }

        async getCartWithInventory(validate = true, autoDeselect = false) {
            const cart = await this.getCart(validate);
            if (!this.isLoggedIn() || cart.local) {
                return { ...cart, inventoryStatus: null };
            }
            try {
                const inventory = await this.checkCartInventory(autoDeselect);
                const cartWithInventory = this.applyInventoryStatusToCart(cart, inventory);
                this._emit('cartWithInventoryLoaded', cartWithInventory);
                return cartWithInventory;
            } catch (e) {
                this._log('get cart with inventory failed, fallback to normal cart', e);
                this._emit('cartLoaded', cart);
                return { ...cart, inventoryStatus: null, inventoryError: e.message };
            }
        }

        applyInventoryStatusToCart(cart, inventoryResult) {
            if (!cart || !cart.items || !inventoryResult) {
                return cart;
            }
            const shortageMap = {};
            if (inventoryResult.shortageItems) {
                inventoryResult.shortageItems.forEach(item => {
                    if (item.skuId) {
                        shortageMap[item.skuId] = item;
                    }
                });
            }
            const allStatusMap = {};
            if (inventoryResult.items) {
                inventoryResult.items.forEach(item => {
                    if (item.skuId) {
                        allStatusMap[item.skuId] = item;
                    }
                });
            }
            let hasShortageInSelected = false;
            const updatedItems = cart.items.map(item => {
                const updated = { ...item };
                const status = allStatusMap[item.skuId];
                const shortage = shortageMap[item.skuId];

                if (status) {
                    updated.stock = status.stock !== undefined ? status.stock : item.stock;
                    updated.available = status.available !== undefined ? status.available : true;
                    updated.availableQuantity = status.availableQuantity !== undefined
                        ? status.availableQuantity : item.availableQuantity;
                }

                if (shortage) {
                    updated.available = false;
                    updated.stockShortage = true;
                    updated.invalidMessage = shortage.shortageReason || '库存不足';
                    updated.shortageReason = shortage.shortageReason || '库存不足';
                    updated.requestedQuantity = shortage.requestedQuantity || item.quantity;
                    updated.availableQuantity = shortage.availableQuantity || shortage.stock || 0;
                    if (shortage.itemImage) {
                        updated.itemImage = shortage.itemImage;
                    }
                    if (shortage.itemName) {
                        updated.itemName = shortage.itemName;
                    }
                    if (shortage.unitPrice != null) {
                        updated.unitPrice = shortage.unitPrice;
                    }
                    if (item.selected) {
                        hasShortageInSelected = true;
                    }
                    updated.selected = false;
                    updated.stockStatusClass = 'stock-shortage';
                } else {
                    updated.stockStatusClass = 'stock-available';
                    if (!updated.invalidMessage || updated.invalidMessage === '库存不足') {
                        updated.invalidMessage = null;
                    }
                    if (updated.stockShortage !== undefined) {
                        delete updated.stockShortage;
                    }
                }
                return updated;
            });
            const shortageSkuIds = Object.keys(shortageMap);
            const result = {
                ...cart,
                items: updatedItems,
                hasStockShortage: shortageSkuIds.length > 0,
                hasShortageInSelected: hasShortageInSelected,
                inventoryStatus: {
                    allAvailable: inventoryResult.allAvailable,
                    hasShortage: inventoryResult.hasShortage,
                    shortageCount: shortageSkuIds.length,
                    shortageSkuIds: shortageSkuIds,
                    shortageItems: inventoryResult.shortageItems,
                    checkFailed: false
                }
            };
            this._emit('inventoryStatusApplied', result);
            return result;
        }

        applyStockShortageStyles(containerSelector = '.cart-items-container', options = {}) {
            const container = document.querySelector(containerSelector);
            if (!container) return;
            const self = this;
            const itemSelector = options.itemSelector || '.cart-item';
            const checkboxSelector = options.checkboxSelector || 'input[type="checkbox"], .item-checkbox';
            const skuIdAttr = options.skuIdAttr || 'data-sku-id';
            const shortageClass = options.shortageClass || 'stock-shortage';

            const applyShortageClass = (el, skuId) => {
                const shortageSkuIds = options.shortageSkuIds || [];
                const shortageMap = options.shortageMap || {};
                if (shortageSkuIds.includes(skuId) || shortageMap[skuId]) {
                    el.classList.add(shortageClass);
                    const shortageInfo = shortageMap[skuId];
                    if (shortageInfo && !el.getAttribute('data-shortage-reason')) {
                        el.setAttribute('data-shortage-reason',
                            shortageInfo.shortageReason || shortageInfo.reason || '库存不足');
                    }
                    const checkbox = el.querySelector(checkboxSelector);
                    if (checkbox) {
                        checkbox.checked = false;
                        checkbox.disabled = true;
                    }
                }
            };

            const itemElements = container.querySelectorAll(itemSelector);
            itemElements.forEach(el => {
                const skuId = el.dataset.skuId
                    || el.getAttribute(skuIdAttr)
                    || el.getAttribute('skuId');
                if (!skuId) return;
                applyShortageClass(el, skuId);

                const checkbox = el.querySelector(checkboxSelector);
                if (checkbox) {
                    checkbox.addEventListener('change', function (e) {
                        if (el.classList.contains(shortageClass)) {
                            e.preventDefault();
                            e.stopPropagation();
                            this.checked = false;
                            const reason = el.getAttribute('data-shortage-reason')
                                || el.dataset.shortageReason
                                || '库存不足';
                            self._showStockShortageToast(reason);
                        }
                    });
                    checkbox.addEventListener('click', function (e) {
                        if (el.classList.contains(shortageClass)) {
                            e.preventDefault();
                            e.stopPropagation();
                            const reason = el.getAttribute('data-shortage-reason')
                                || el.dataset.shortageReason
                                || '库存不足';
                            self._showStockShortageToast(reason);
                            return false;
                        }
                    });
                }

                const clickableAreas = el.querySelectorAll(options.clickableSelector || `${checkboxSelector}, .select-area`);
                clickableAreas.forEach(area => {
                    area.addEventListener('click', function (e) {
                        if (el.classList.contains(shortageClass)) {
                            e.preventDefault();
                            e.stopPropagation();
                            const reason = el.getAttribute('data-shortage-reason')
                                || el.dataset.shortageReason
                                || '库存不足';
                            self._showStockShortageToast(reason);
                            return false;
                        }
                    });
                });
            });

            if (typeof MutationObserver !== 'undefined') {
                const observer = new MutationObserver(function (mutations) {
                    mutations.forEach(mutation => {
                        if (mutation.type === 'childList') {
                            mutation.addedNodes.forEach(node => {
                                if (node.nodeType === 1 && node.matches(itemSelector)) {
                                    const skuId = node.dataset.skuId
                                        || node.getAttribute(skuIdAttr)
                                        || node.getAttribute('skuId');
                                    if (skuId) applyShortageClass(node, skuId);
                                }
                            });
                        }
                    });
                });
                observer.observe(container, { childList: true, subtree: true });
            }
        }

        _showStockShortageToast(message) {
            if (typeof this.showToast === 'function') {
                this.showToast({ type: 'warning', message: message, duration: 2000 });
            } else if (typeof window !== 'undefined') {
                try {
                    const existing = document.querySelector('.cart-stock-shortage-toast');
                    if (existing) existing.remove();
                    const toast = document.createElement('div');
                    toast.className = 'cart-stock-shortage-toast';
                    toast.style.cssText = 'position:fixed;top:20px;left:50%;transform:translateX(-50%);' +
                        'background:rgba(245,108,108,0.95);color:#fff;padding:12px 24px;border-radius:8px;' +
                        'z-index:99999;font-size:14px;box-shadow:0 4px 12px rgba(0,0,0,0.15);';
                    toast.innerHTML = '⚠️ ' + message;
                    document.body.appendChild(toast);
                    setTimeout(() => { toast.style.opacity = '0'; toast.style.transition = 'opacity 0.3s'; }, 2000);
                    setTimeout(() => toast.remove(), 2400);
                } catch (e) {
                    console.warn('Failed to show toast', e);
                }
            }
        }

        _trackInventoryShortage(shortageItems) {
            try {
                if (!shortageItems || !shortageItems.length) return;
                this.track('inventory_shortage_warning', {
                    shortageCount: shortageItems.length,
                    items: shortageItems,
                    skuId: shortageItems[0] ? shortageItems[0].skuId : undefined,
                    spuId: shortageItems[0] ? shortageItems[0].spuId : undefined,
                    itemName: shortageItems[0] ? shortageItems[0].itemName : undefined,
                    categoryId: shortageItems[0] ? shortageItems[0].categoryId : undefined,
                    categoryName: shortageItems[0] ? shortageItems[0].categoryName : undefined,
                    requestedQuantity: shortageItems[0] ? shortageItems[0].requestedQuantity : undefined,
                    availableQuantity: shortageItems[0] ? shortageItems[0].availableQuantity : undefined,
                    stock: shortageItems[0] ? shortageItems[0].stock : undefined,
                    shortageReason: shortageItems[0] ? shortageItems[0].shortageReason : undefined
                });
            } catch (e) {
                this._log('track inventory shortage failed', e);
            }
        }

        async createCheckout(options = {}) {
            const body = {
                skuIds: options.skuIds || null,
                addressId: options.addressId || null,
                couponId: options.couponId || null,
                remark: options.remark || null,
                source: options.source || this.source
            };

            if (this.options.checkInventoryBeforeCheckout !== false) {
                try {
                    const inventory = await this.checkCartInventory(true);
                    if (inventory && inventory.hasShortage) {
                        this._log('Stock shortage detected, blocked checkout. Shortage items:',
                            inventory.shortageItems);

                        if (typeof this.showToast === 'function') {
                            const names = inventory.shortageItems
                                .map(i => i.itemName || i.skuId)
                                .join('、');
                            this.showToast({
                                type: 'error',
                                message: '以下商品库存不足：' + names,
                                duration: 3000
                            });
                        }

                        const err = new Error('商品库存不足，请移除后再结算');
                        err.inventoryStatus = inventory;
                        err.code = 'STOCK_SHORTAGE';
                        err.shortageItems = inventory.shortageItems;
                        this._emit('checkoutStockShortage', inventory);
                        throw err;
                    }
                } catch (e) {
                    if (e.code === 'STOCK_SHORTAGE') {
                        throw e;
                    }
                    this._log('Pre-checkout inventory check failed, blocked checkout', e);
                    const err = new Error('库存校验失败，请稍后重试：' + (e.message || '未知错误'));
                    err.code = 'STOCK_CHECK_FAILED';
                    err.cause = e;
                    throw err;
                }
            }

            const result = await this._request('/api/checkout', {
                method: 'POST',
                body: body
            });

            if (result && result.hasStockShortage && result.stockShortageItems) {
                this._trackInventoryShortage(result.stockShortageItems);
                this._emit('checkoutCreatedWithStockShortage', result);
                const err = new Error('商品库存不足，请移除后再结算');
                err.code = 'STOCK_SHORTAGE';
                err.inventoryStatus = result;
                err.shortageItems = result.stockShortageItems;
                throw err;
            }

            this._emit('checkoutCreated', result);
            return result;
        }

        async getCheckout(checkoutToken) {
            if (!checkoutToken) {
                throw new Error('checkoutToken is required');
            }
            return await this._request('/api/checkout/' + encodeURIComponent(checkoutToken));
        }

        async confirmCheckout(options) {
            if (!options || !options.checkoutToken) {
                throw new Error('checkoutToken is required');
            }
            const result = await this._request('/api/checkout/confirm', {
                method: 'POST',
                body: options
            });
            this._emit('checkoutConfirmed', result);
            return result;
        }

        async cancelCheckout(checkoutToken) {
            if (!checkoutToken) {
                throw new Error('checkoutToken is required');
            }
            const result = await this._request('/api/checkout/cancel/' + encodeURIComponent(checkoutToken), {
                method: 'POST'
            });
            this._emit('checkoutCanceled', { checkoutToken, canceled: result });
            return result;
        }

        async refreshCheckout(checkoutToken) {
            if (!checkoutToken) {
                throw new Error('checkoutToken is required');
            }
            const result = await this._request('/api/checkout/refresh/' + encodeURIComponent(checkoutToken), {
                method: 'POST'
            });
            this._emit('checkoutRefreshed', result);
            return result;
        }

        async listCheckouts(status) {
            let url = '/api/checkout/list';
            if (status != null) {
                url += '?status=' + encodeURIComponent(status);
            }
            return await this._request(url);
        }

        goToOrderPage(checkoutToken, options = {}) {
            const orderPageUrl = options.orderPageUrl || 'checkout-order.html';
            const params = new URLSearchParams();
            params.append('token', checkoutToken);
            if (options.redirectUrl) {
                params.append('redirect_url', options.redirectUrl);
            }
            const targetUrl = orderPageUrl + '?' + params.toString();
            this._log('goToOrderPage', targetUrl);
            if (options.replace) {
                window.location.replace(targetUrl);
            } else {
                window.location.href = targetUrl;
            }
        }

        checkoutAndGoToOrderPage(checkoutOptions = {}, orderPageOptions = {}) {
            return this.createCheckout(checkoutOptions).then(result => {
                this.goToOrderPage(result.checkoutToken, orderPageOptions);
                return result;
            });
        }

        async getFavorite() {
            const result = await this._request('/api/favorite');
            this._emit('favoriteChanged', result);
            return result;
        }

        async isFavorited(skuId) {
            if (!skuId) {
                throw new Error('skuId is required');
            }
            return await this._request('/api/favorite/check/' + encodeURIComponent(skuId));
        }

        async getFavoriteCount() {
            return await this._request('/api/favorite/count');
        }

        async addFavoriteItem(item) {
            if (!item || !item.skuId) {
                throw new Error('skuId is required');
            }
            const result = await this._request('/api/favorite/item', {
                method: 'POST',
                body: item
            });
            this._emit('favoriteChanged', result);
            this._emit('favoriteAdded', { skuId: item.skuId, favorite: result });
            return result;
        }

        async addFavoriteItems(items) {
            if (!Array.isArray(items) || items.length === 0) {
                throw new Error('items must be a non-empty array');
            }
            const result = await this._request('/api/favorite/items', {
                method: 'POST',
                body: items
            });
            this._emit('favoriteChanged', result);
            return result;
        }

        async removeFavoriteItem(skuId) {
            if (!skuId) {
                throw new Error('skuId is required');
            }
            const result = await this._request('/api/favorite/item/' + encodeURIComponent(skuId), {
                method: 'DELETE'
            });
            this._emit('favoriteChanged', result);
            this._emit('favoriteRemoved', { skuId, favorite: result });
            return result;
        }

        async removeFavoriteItems(skuIds) {
            if (!Array.isArray(skuIds) || skuIds.length === 0) {
                throw new Error('skuIds must be a non-empty array');
            }
            const result = await this._request('/api/favorite/items', {
                method: 'DELETE',
                body: skuIds
            });
            this._emit('favoriteChanged', result);
            return result;
        }

        async clearFavorite() {
            const result = await this._request('/api/favorite/clear', {
                method: 'DELETE'
            });
            this._emit('favoriteChanged', result);
            this._emit('favoriteCleared', result);
            return result;
        }

        async favoriteAddToCart(skuIds, removeFromFavorite = false) {
            if (!Array.isArray(skuIds) || skuIds.length === 0) {
                throw new Error('skuIds must be a non-empty array');
            }
            const result = await this._request('/api/favorite/cart/add', {
                method: 'POST',
                body: { skuIds, removeFromFavorite }
            });
            this._emit('cartChanged', result);
            this._emit('favoriteToCart', { skuIds, result });
            return result;
        }

        async favoriteAllToCart(removeFromFavorite = false) {
            const result = await this._request('/api/favorite/cart/add-all', {
                method: 'POST',
                body: { removeFromFavorite }
            });
            this._emit('cartChanged', result);
            this._emit('favoriteToCart', { all: true, result });
            return result;
        }

        toggleFavorite(item) {
            if (!item || !item.skuId) {
                return Promise.reject(new Error('skuId is required'));
            }
            return this.isFavorited(item.skuId).then(favorited => {
                if (favorited) {
                    return this.removeFavoriteItem(item.skuId);
                } else {
                    return this.addFavoriteItem(item);
                }
            });
        }

        on(event, callback) {
            if (!_isFunction(callback)) return;
            (this.eventListeners[event] || (this.eventListeners[event] = [])).push(callback);
            return () => this.off(event, callback);
        }

        off(event, callback) {
            if (!this.eventListeners[event]) return;
            this.eventListeners[event] = this.eventListeners[event]
                .filter(cb => cb !== callback);
        }

        _emit(event, data) {
            if (!this.eventListeners[event]) return;
            this.eventListeners[event].forEach(cb => {
                try { cb(data); } catch (e) { this._log('listener error', event, e); }
            });
        }

        _initTracking(options) {
            this._trackEnabled = options.trackEnabled !== false;
            this._trackAuto = options.trackAuto !== false;
            this._trackBatchSize = options.trackBatchSize || 20;
            this._trackFlushInterval = options.trackFlushInterval || 5000;
            this._trackEndpoint = options.trackEndpoint || '/api/track';
            this._trackEvents = [];
            this._trackSessionId = this._getSessionId();
            this._trackFlushTimer = null;
            this._trackClickSelector = options.trackClickSelector || '[data-track]';
            this._trackSuperProperties = options.trackSuperProperties || {};

            if (this._trackAuto) {
                this._setupAutoTracking();
            }

            this._startFlushTimer();

            this._log('tracking initialized', {
                enabled: this._trackEnabled,
                auto: this._trackAuto,
                batchSize: this._trackBatchSize,
                flushInterval: this._trackFlushInterval
            });
        }

        _getSessionId() {
            const key = STORAGE_KEY_PREFIX + 'session_id';
            let session = sessionStorage.getItem(key);
            if (!session) {
                session = 'sess_' + _guid();
                sessionStorage.setItem(key, session);
            }
            return session;
        }

        _setupAutoTracking() {
            const self = this;

            this.on('itemAdded', function (data) {
                const item = data.item || {};
                self.track('add_to_cart', {
                    skuId: item.skuId,
                    spuId: item.spuId,
                    categoryId: item.categoryId,
                    categoryName: item.categoryName,
                    shopId: item.shopId,
                    itemName: item.itemName,
                    itemImage: item.itemImage,
                    unitPrice: item.unitPrice,
                    originalPrice: item.originalPrice,
                    quantity: item.quantity || 1,
                    addSource: item.addSource
                });
            });

            this.on('itemRemoved', function (data) {
                self.track('remove_from_cart', {
                    skuId: data.skuId
                });
            });

            this.on('quantityChanged', function (data) {
                self.track('update_quantity', {
                    skuId: data.skuId,
                    delta: data.delta,
                    newQuantity: data.newQuantity
                });
            });

            this.on('cartCleared', function () {
                self.track('clear_cart', {});
            });

            this.on('checkoutCreated', function (data) {
                self.track('checkout_create', {
                    checkoutToken: data.checkoutToken,
                    totalAmount: data.totalAmount,
                    itemCount: data.itemCount,
                    skuIds: data.skuIds
                });
            });

            this.on('checkoutConfirmed', function (data) {
                self.track('checkout_confirm', {
                    checkoutToken: data.checkoutToken,
                    orderNo: data.orderNo,
                    payAmount: data.payAmount
                });
            });

            this.on('checkoutCanceled', function (data) {
                self.track('checkout_cancel', {
                    checkoutToken: data.checkoutToken
                });
            });

            this._setupClickTracking();

            this._trackPageView();

            if (typeof window !== 'undefined' && window.addEventListener) {
                window.addEventListener('beforeunload', function () {
                    self._flushEvents(true);
                });
                document.addEventListener('visibilitychange', function () {
                    if (document.visibilityState === 'hidden') {
                        self._flushEvents(true);
                    }
                });
            }
        }

        _setupClickTracking() {
            const self = this;
            if (typeof document === 'undefined' || !document.addEventListener) return;

            document.addEventListener('click', function (e) {
                const target = e.target.closest(self._trackClickSelector);
                if (target) {
                    const eventName = target.getAttribute('data-track-event') || 'click';
                    const eventData = {};
                    const attrs = target.attributes;
                    for (let i = 0; i < attrs.length; i++) {
                        const attr = attrs[i];
                        if (attr.name.startsWith('data-track-') && attr.name !== 'data-track-event') {
                            const key = attr.name.substring(11).replace(/-([a-z])/g, function (g) { return g[1].toUpperCase(); });
                            eventData[key] = attr.value;
                        }
                    }
                    eventData.elementId = target.id;
                    eventData.elementClass = target.className;
                    eventData.elementText = target.textContent ? target.textContent.trim().substring(0, 100) : '';

                    self.track(eventName, eventData);
                }
            }, true);
        }

        _trackPageView() {
            if (typeof window === 'undefined' || typeof document === 'undefined') return;

            this.track('page_view', {
                pageUrl: window.location.href,
                pageTitle: document.title,
                referrer: document.referrer,
                screenWidth: window.screen ? window.screen.width : 0,
                screenHeight: window.screen ? window.screen.height : 0,
                language: navigator ? navigator.language : ''
            });
        }

        track(eventName, properties) {
            if (!this._trackEnabled) return;
            if (!eventName) return;

            const mergedProps = Object.assign({}, this._trackSuperProperties, properties);
            const event = {
                eventType: eventName,
                eventId: 'evt_' + _guid(),
                timestamp: Date.now(),
                tenantId: this.tenantId,
                bizType: this.bizType,
                userId: this.userId,
                anonymousId: this._anonymousId,
                sessionId: this._trackSessionId,
                source: this.source,
                clientVersion: VERSION,
                properties: mergedProps
            };

            if (mergedProps.skuId !== undefined) event.skuId = String(mergedProps.skuId);
            if (mergedProps.spuId !== undefined) event.spuId = String(mergedProps.spuId);
            if (mergedProps.categoryId !== undefined) event.categoryId = String(mergedProps.categoryId);
            if (mergedProps.categoryName !== undefined) event.categoryName = String(mergedProps.categoryName);
            if (mergedProps.shopId !== undefined) event.shopId = String(mergedProps.shopId);
            if (mergedProps.itemName !== undefined) event.itemName = String(mergedProps.itemName);
            if (mergedProps.itemImage !== undefined) event.itemImage = String(mergedProps.itemImage);
            if (mergedProps.unitPrice !== undefined) event.unitPrice = Number(mergedProps.unitPrice);
            if (mergedProps.originalPrice !== undefined) event.originalPrice = Number(mergedProps.originalPrice);
            if (mergedProps.quantity !== undefined) event.quantity = Number(mergedProps.quantity);
            if (mergedProps.oldQuantity !== undefined) event.oldQuantity = Number(mergedProps.oldQuantity);
            if (mergedProps.newQuantity !== undefined) event.newQuantity = Number(mergedProps.newQuantity);
            if (mergedProps.delta !== undefined) event.delta = Number(mergedProps.delta);
            if (mergedProps.checkoutToken !== undefined) event.checkoutToken = String(mergedProps.checkoutToken);
            if (mergedProps.totalAmount !== undefined) event.cartTotalAmount = Number(mergedProps.totalAmount);
            if (mergedProps.cartTotalAmount !== undefined) event.cartTotalAmount = Number(mergedProps.cartTotalAmount);
            if (mergedProps.itemCount !== undefined) event.cartItemCount = Number(mergedProps.itemCount);
            if (mergedProps.cartItemCount !== undefined) event.cartItemCount = Number(mergedProps.cartItemCount);
            if (mergedProps.couponId !== undefined) event.couponId = String(mergedProps.couponId);
            if (mergedProps.couponCode !== undefined) event.couponCode = String(mergedProps.couponCode);
            if (mergedProps.discountAmount !== undefined) event.discountAmount = Number(mergedProps.discountAmount);
            if (mergedProps.orderNo !== undefined) event.orderNo = String(mergedProps.orderNo);
            if (mergedProps.payAmount !== undefined) event.payAmount = Number(mergedProps.payAmount);
            if (mergedProps.cancelReason !== undefined) event.cancelReason = String(mergedProps.cancelReason);
            if (mergedProps.addSource !== undefined) event.addSource = String(mergedProps.addSource);
            if (mergedProps.elementId !== undefined) event.elementId = String(mergedProps.elementId);
            if (mergedProps.elementClass !== undefined) event.elementClass = String(mergedProps.elementClass);
            if (mergedProps.elementText !== undefined) event.elementText = String(mergedProps.elementText);
            if (mergedProps.position !== undefined) event.position = Number(mergedProps.position);

            if (typeof window !== 'undefined') {
                event.pageUrl = window.location.href;
                event.pageTitle = document ? document.title : '';
                event.userAgent = navigator ? navigator.userAgent : '';
            }

            this._trackEvents.push(event);
            this._emit('trackEvent', event);
            this._log('track event', eventName, event);

            if (this._trackEvents.length >= this._trackBatchSize) {
                this._flushEvents();
            }
        }

        setSuperProperty(key, value) {
            if (key) {
                this._trackSuperProperties[key] = value;
            }
        }

        setSuperProperties(props) {
            if (props && typeof props === 'object') {
                Object.assign(this._trackSuperProperties, props);
            }
        }

        getSuperProperties() {
            return Object.assign({}, this._trackSuperProperties);
        }

        clearSuperProperties() {
            this._trackSuperProperties = {};
        }

        _startFlushTimer() {
            const self = this;
            if (this._trackFlushTimer) {
                clearInterval(this._trackFlushTimer);
            }
            this._trackFlushTimer = setInterval(function () {
                if (self._trackEvents.length > 0) {
                    self._flushEvents();
                }
            }, this._trackFlushInterval);
        }

        _flushEvents(sync) {
            if (!this._trackEnabled || this._trackEvents.length === 0) return;

            const events = this._trackEvents.slice();
            this._trackEvents = [];

            const self = this;
            const url = this.baseUrl + this._trackEndpoint + '/events';
            const body = JSON.stringify(events);

            if (sync && typeof navigator !== 'undefined' && navigator.sendBeacon) {
                try {
                    const blob = new Blob([body], { type: 'application/json' });
                    navigator.sendBeacon(url, blob);
                    this._log('flush events via beacon', events.length);
                    return;
                } catch (e) {
                    this._log('beacon failed, fallback to fetch', e);
                }
            }

            const headers = {
                'Content-Type': 'application/json',
                'X-Tenant-Id': this.tenantId,
                'X-Biz-Type': this.bizType,
                'X-User-Id': this._getCurrentUserId(),
                'X-Source': this.source,
                'X-Client-Version': VERSION
            };

            if (typeof this.onBeforeRequest === 'function') {
                Object.assign(headers, this.onBeforeRequest() || {});
            }

            if (this.token) {
                headers['Authorization'] = 'Bearer ' + this.token;
            }

            const fetchOptions = {
                method: 'POST',
                headers: headers,
                body: body
            };

            if (sync) {
                try {
                    const xhr = new XMLHttpRequest();
                    xhr.open('POST', url, false);
                    Object.keys(headers).forEach(function (key) {
                        xhr.setRequestHeader(key, headers[key]);
                    });
                    xhr.send(body);
                    this._log('flush events sync', events.length);
                } catch (e) {
                    this._log('sync flush failed', e);
                    this._trackEvents = events.concat(this._trackEvents);
                }
            } else {
                fetch(url, fetchOptions)
                    .then(function (res) { return res.text(); })
                    .then(function () {
                        self._log('flush events success', events.length);
                    })
                    .catch(function (err) {
                        self._log('flush events failed', err);
                        self._trackEvents = events.concat(self._trackEvents);
                        if (self._trackEvents.length > 1000) {
                            self._trackEvents = self._trackEvents.slice(-500);
                        }
                    });
            }
        }

        flushEvents() {
            this._flushEvents();
        }

        getPendingEventCount() {
            return this._trackEvents.length;
        }

        trackAddToCart(item) {
            this.track('add_to_cart', {
                skuId: item.skuId,
                spuId: item.spuId,
                categoryId: item.categoryId,
                categoryName: item.categoryName,
                shopId: item.shopId,
                itemName: item.itemName,
                itemImage: item.itemImage,
                unitPrice: item.unitPrice,
                originalPrice: item.originalPrice,
                quantity: item.quantity || 1,
                addSource: item.addSource
            });
        }

        trackRemoveFromCart(skuId) {
            this.track('remove_from_cart', { skuId: skuId });
        }

        trackUpdateQuantity(skuId, oldQty, newQty) {
            this.track('update_quantity', {
                skuId: skuId,
                oldQuantity: oldQty,
                newQuantity: newQty,
                delta: newQty - oldQty
            });
        }

        trackCheckoutClick(position) {
            this.track('checkout_click', {
                position: position || 'default'
            });
        }

        trackCheckoutCreate(checkoutData) {
            this.track('checkout_create', checkoutData || {});
        }

        trackCheckoutConfirm(checkoutData) {
            this.track('checkout_confirm', checkoutData || {});
        }

        trackCheckoutCancel(checkoutToken) {
            this.track('checkout_cancel', { checkoutToken: checkoutToken });
        }

        trackApplyCoupon(couponId, couponCode) {
            this.track('apply_coupon', {
                couponId: couponId,
                couponCode: couponCode
            });
        }

        trackRemoveCoupon(couponId) {
            this.track('remove_coupon', { couponId: couponId });
        }

        static create(options) {
            return new CartHubSDK(options);
        }

        static get VERSION() {
            return VERSION;
        }
    }

    return CartHubSDK;
}));

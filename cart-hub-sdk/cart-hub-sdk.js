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
            this.eventListeners = {};
            this._anonymousId = this._getAnonymousId();
            this._localCache = this._loadLocalCache();
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

        setUserId(userId) {
            const oldUserId = this.userId;
            this.userId = userId;
            this._log('setUserId', { oldUserId, newUserId: userId });
            this._emit('userChanged', { oldUserId, newUserId: userId });
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
                if (result.code !== 200 && result.code !== 0) {
                    this._emit('error', { url, error: result });
                    throw Object.assign(new Error(result.message || 'Request failed'), result);
                }
                this._emit('success', { url, data: result.data });
                return result.data;
            } catch (error) {
                clearTimeout(timeoutId);
                if (error.name === 'AbortError') {
                    throw new Error('Request timeout');
                }
                throw error;
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

        async addItem(item) {
            const result = await this._request('/api/cart/item', {
                method: 'POST',
                body: item
            });
            this._emit('itemAdded', { item, result });
            this._emit('cartChanged', result);
            return result;
        }

        async updateItem(item) {
            const result = await this._request('/api/cart/item', {
                method: 'PUT',
                body: item
            });
            this._emit('itemUpdated', { item, result });
            this._emit('cartChanged', result);
            return result;
        }

        async incrementQuantity(skuId, delta = 1) {
            const qs = new URLSearchParams({ skuId, delta: String(delta) });
            const result = await this._request('/api/cart/item/quantity?' + qs.toString(), {
                method: 'PATCH'
            });
            this._emit('quantityChanged', { skuId, delta, result });
            this._emit('cartChanged', result);
            return result;
        }

        async removeItem(skuId) {
            const qs = new URLSearchParams({ skuId });
            const result = await this._request('/api/cart/item?' + qs.toString(), {
                method: 'DELETE'
            });
            this._emit('itemRemoved', { skuId, result });
            this._emit('cartChanged', result);
            return result;
        }

        async batchRemove(skuIds) {
            const result = await this._request('/api/cart/items', {
                method: 'DELETE',
                body: { skuIds }
            });
            this._emit('itemsBatchRemoved', { skuIds, result });
            this._emit('cartChanged', result);
            return result;
        }

        async clearCart() {
            const result = await this._request('/api/cart/clear', { method: 'DELETE' });
            this._emit('cartCleared', result);
            this._emit('cartChanged', result);
            return result;
        }

        async getCart(validate = true) {
            const qs = new URLSearchParams({ validate: String(validate) });
            const result = await this._request('/api/cart?' + qs.toString());
            this._emit('cartLoaded', result);
            return result;
        }

        async getCartSimple() {
            const result = await this._request('/api/cart/simple');
            this._emit('cartLoaded', result);
            return result;
        }

        async getItemCount() {
            return this._request('/api/cart/count');
        }

        async getCartSummary() {
            return this._request('/api/cart/summary');
        }

        async mergeCart(options = {}) {
            const localItems = this._localCache.items;
            const body = Object.assign({
                items: localItems,
                sourceUserId: this._anonymousId,
                overwrite: false
            }, options);
            const result = await this._request('/api/cart/merge', {
                method: 'POST',
                body
            });
            this.clearLocalCart();
            this._emit('merged', result);
            return result;
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
            const qs = new URLSearchParams();
            if (options.snapshotName) qs.set('snapshotName', options.snapshotName);
            if (options.snapshotType) qs.set('snapshotType', options.snapshotType);
            if (options.orderNo) qs.set('orderNo', options.orderNo);
            return this._request('/api/cart/snapshot?' + qs.toString(), {
                method: 'POST'
            });
        }

        async getSnapshot(snapshotId) {
            return this._request('/api/cart/snapshot/' + snapshotId);
        }

        async restoreSnapshot(snapshotId) {
            return this._request('/api/cart/snapshot/restore/' + snapshotId, {
                method: 'POST'
            });
        }

        async listMySnapshots() {
            return this._request('/api/cart/snapshot/list');
        }

        async deleteSnapshot(snapshotId) {
            return this._request('/api/cart/snapshot/' + snapshotId, { method: 'DELETE' });
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

        async setItemRemark(skuId, remark) {
            const result = await this._request('/api/cart/item/remark', {
                method: 'PUT',
                body: { skuId, remark }
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

        async removeItemRemark(skuId) {
            const qs = new URLSearchParams({ skuId });
            const result = await this._request('/api/cart/item/remark?' + qs.toString(), {
                method: 'DELETE'
            });
            this._emit('remarkChanged', { skuId, remark: null });
            this._emit('cartChanged', result);
            return result;
        }

        async clearAllItemRemarks() {
            const result = await this._request('/api/cart/items/remarks', { method: 'DELETE' });
            this._emit('remarkChanged', { skuId: null, remark: null, allCleared: true });
            this._emit('cartChanged', result);
            return result;
        }

        async batchSort(sortItems) {
            if (!Array.isArray(sortItems)) {
                throw new Error('sortItems must be an array of {skuId, sortWeight}');
            }
            const items = sortItems.map((it, idx) => ({
                skuId: it.skuId,
                sortWeight: it.sortWeight != null ? it.sortWeight : idx
            }));
            const result = await this._request('/api/cart/items/sort', {
                method: 'PUT',
                body: { sortItems: items }
            });
            this._emit('sortChanged', items);
            this._emit('cartChanged', result);
            return result;
        }

        async reorderCartBySkus(orderedSkuIds) {
            if (!Array.isArray(orderedSkuIds)) {
                throw new Error('orderedSkuIds must be an array');
            }
            const sortItems = orderedSkuIds.map((skuId, idx) => ({
                skuId,
                sortWeight: (idx + 1) * 10
            }));
            return this.batchSort(sortItems);
        }

        async createCheckout(options = {}) {
            const body = {
                skuIds: options.skuIds || null,
                addressId: options.addressId || null,
                couponId: options.couponId || null,
                remark: options.remark || null,
                source: options.source || this.source
            };
            const result = await this._request('/api/checkout', {
                method: 'POST',
                body: body
            });
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

        static create(options) {
            return new CartHubSDK(options);
        }

        static get VERSION() {
            return VERSION;
        }
    }

    return CartHubSDK;
}));

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
            return this._request('/api/cart/item', {
                method: 'POST',
                body: item
            });
        }

        async updateItem(item) {
            return this._request('/api/cart/item', {
                method: 'PUT',
                body: item
            });
        }

        async incrementQuantity(skuId, delta = 1) {
            const qs = new URLSearchParams({ skuId, delta: String(delta) });
            return this._request('/api/cart/item/quantity?' + qs.toString(), {
                method: 'PATCH'
            });
        }

        async removeItem(skuId) {
            const qs = new URLSearchParams({ skuId });
            return this._request('/api/cart/item?' + qs.toString(), {
                method: 'DELETE'
            });
        }

        async batchRemove(skuIds) {
            return this._request('/api/cart/items', {
                method: 'DELETE',
                body: { skuIds }
            });
        }

        async clearCart() {
            return this._request('/api/cart/clear', { method: 'DELETE' });
        }

        async getCart(validate = true) {
            const qs = new URLSearchParams({ validate: String(validate) });
            return this._request('/api/cart?' + qs.toString());
        }

        async getCartSimple() {
            return this._request('/api/cart/simple');
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

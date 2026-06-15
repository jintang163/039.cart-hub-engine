package com.carhub.common.context;

import com.carhub.common.constant.CartConstant;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

public class CartContextHolder {

    private static final ThreadLocal<CartContext> CONTEXT_HOLDER = new ThreadLocal<>();

    public static CartContext getContext() {
        CartContext context = CONTEXT_HOLDER.get();
        if (context == null) {
            context = buildFromRequest();
            CONTEXT_HOLDER.set(context);
        }
        return context;
    }

    public static void setContext(CartContext context) {
        CONTEXT_HOLDER.set(context);
    }

    public static void clear() {
        CONTEXT_HOLDER.remove();
    }

    public static String getTenantId() {
        return getContext().getTenantId();
    }

    public static String getBizType() {
        return getContext().getBizType();
    }

    public static String getUserId() {
        return getContext().getUserId();
    }

    public static String getSource() {
        return getContext().getSource();
    }

    private static CartContext buildFromRequest() {
        CartContext context = new CartContext();
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String tenantId = request.getHeader(CartConstant.HEADER_TENANT_ID);
            String bizType = request.getHeader(CartConstant.HEADER_BIZ_TYPE);
            String userId = request.getHeader(CartConstant.HEADER_USER_ID);
            context.setTenantId(StringUtils.defaultIfBlank(tenantId, CartConstant.DEFAULT_TENANT_ID));
            context.setBizType(StringUtils.defaultIfBlank(bizType, CartConstant.BIZ_TYPE_ECOMMERCE));
            context.setUserId(userId);
            context.setSource(request.getHeader("X-Source"));
        } else {
            context.setTenantId(CartConstant.DEFAULT_TENANT_ID);
            context.setBizType(CartConstant.BIZ_TYPE_ECOMMERCE);
        }
        return context;
    }

}

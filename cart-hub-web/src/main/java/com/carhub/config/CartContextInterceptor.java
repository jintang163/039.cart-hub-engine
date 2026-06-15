package com.carhub.config;

import com.carhub.common.constant.CartConstant;
import com.carhub.common.context.CartContext;
import com.carhub.common.context.CartContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Slf4j
public class CartContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenantId = request.getHeader(CartConstant.HEADER_TENANT_ID);
        String bizType = request.getHeader(CartConstant.HEADER_BIZ_TYPE);
        String userId = request.getHeader(CartConstant.HEADER_USER_ID);
        String source = request.getHeader("X-Source");

        if (StringUtils.isBlank(tenantId)) {
            tenantId = CartConstant.DEFAULT_TENANT_ID;
        }
        if (StringUtils.isBlank(bizType)) {
            bizType = CartConstant.BIZ_TYPE_ECOMMERCE;
        }

        CartContext context = new CartContext();
        context.setTenantId(tenantId);
        context.setBizType(bizType);
        context.setUserId(userId);
        context.setSource(source);
        context.setClientIp(getClientIp(request));
        context.setUserAgent(request.getHeader("User-Agent"));
        context.setTraceId(request.getHeader("X-Trace-Id"));

        CartContextHolder.setContext(context);

        if (log.isDebugEnabled()) {
            log.debug("CartContext: tenantId={}, bizType={}, userId={}, source={}",
                    tenantId, bizType, userId, source);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CartContextHolder.clear();
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (StringUtils.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (StringUtils.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (StringUtils.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (StringUtils.isNotBlank(ip) && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

}

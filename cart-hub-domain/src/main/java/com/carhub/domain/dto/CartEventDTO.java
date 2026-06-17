package com.carhub.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartEventDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "事件类型不能为空")
    private String eventType;

    private String eventId;

    private Long timestamp;

    private String tenantId;

    private String bizType;

    private String userId;

    private String anonymousId;

    private String sessionId;

    private String source;

    private String clientVersion;

    private String pageUrl;

    private String pageTitle;

    private String referrer;

    private String userAgent;

    private String clientIp;

    private String skuId;

    private String spuId;

    private String categoryId;

    private String categoryName;

    private String shopId;

    private String itemName;

    private String itemImage;

    private BigDecimal unitPrice;

    private BigDecimal originalPrice;

    private Integer quantity;

    private Integer oldQuantity;

    private Integer newQuantity;

    private String checkoutToken;

    private BigDecimal cartTotalAmount;

    private Integer cartItemCount;

    private String couponId;

    private String couponCode;

    private BigDecimal discountAmount;

    private String elementId;

    private String elementClass;

    private String elementText;

    private Integer position;

    private Long duration;

    private Map<String, Object> properties;

    private String traceId;

    private Map<String, Object> extInfo;
}

package com.carhub.common.context;

import lombok.Data;

import java.io.Serializable;

@Data
public class CartContext implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tenantId;

    private String bizType;

    private String userId;

    private String source;

    private String clientIp;

    private String userAgent;

    private String traceId;

}

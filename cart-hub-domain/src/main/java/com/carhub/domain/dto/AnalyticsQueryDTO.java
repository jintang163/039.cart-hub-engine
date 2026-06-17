package com.carhub.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tenantId;

    private String bizType;

    private String categoryId;

    private String skuId;

    private String spuId;

    private String source;

    private String startDate;

    private String endDate;

    private Integer topN;

    private String dimension;

    private String granularity;

    private List<String> eventTypes;

    private String sortBy;

    private String sortOrder;

    private Integer page;

    private Integer pageSize;
}

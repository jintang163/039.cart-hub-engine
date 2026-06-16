package com.carhub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportTaskVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;

    private String tenantId;

    private String userId;

    private String status;

    private Integer totalCount;

    private Integer successCount;

    private Integer failCount;

    private String errorReportUrl;

    private Long createTime;

    private Long startTime;

    private Long endTime;

    private String errorMessage;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

}

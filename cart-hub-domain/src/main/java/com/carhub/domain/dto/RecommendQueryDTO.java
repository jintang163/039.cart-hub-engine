package com.carhub.domain.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.io.Serializable;
import java.util.List;

@Data
public class RecommendQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Min(1)
    @Max(50)
    private Integer topN = 10;

    private List<String> currentSkus;

    private String algorithm = "cf";

    private Double minConfidence = 0.1;

    private Double minSupport = 0.01;

}

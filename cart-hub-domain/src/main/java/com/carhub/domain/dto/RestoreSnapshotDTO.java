package com.carhub.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

@Data
public class RestoreSnapshotDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "快照ID不能为空")
    private String snapshotId;

    private Boolean mergeCurrent = false;

    private Long clientVersion;

    private Boolean forceOverwrite = false;
}

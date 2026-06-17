package com.carhub.domain.dto;

import lombok.Data;

import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
public class CreateSnapshotDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Size(max = 256, message = "快照名称不能超过256个字符")
    private String snapshotName;
}

package com.carhub.domain.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class CartImportDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Boolean validateProduct = true;

    private Boolean overwrite = false;

    private String addSource = "excel_import";

}

package com.carhub.controller;

import com.carhub.common.result.R;
import com.carhub.domain.dto.InventoryCheckDTO;
import com.carhub.domain.vo.InventoryCheckVO;
import com.carhub.service.InventoryService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@Api(tags = "库存校验API")
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
@Validated
public class InventoryController {

    private final InventoryService inventoryService;

    @ApiOperation("批量校验商品库存")
    @PostMapping("/check")
    public R<InventoryCheckVO> checkInventory(@RequestBody @Valid InventoryCheckDTO dto) {
        return R.ok(inventoryService.checkInventory(dto.getItems()));
    }

    @ApiOperation("校验当前用户购物车选中商品库存")
    @GetMapping("/check-cart")
    public R<InventoryCheckVO> checkCartInventory(
            @ApiParam("是否自动取消勾选库存不足的商品，默认false")
            @RequestParam(required = false, defaultValue = "false") boolean autoDeselect) {
        return R.ok(inventoryService.checkCartInventory(null, autoDeselect));
    }
}

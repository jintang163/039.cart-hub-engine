package com.carhub.controller;

import com.carhub.common.result.R;
import com.carhub.domain.dto.CheckoutConfirmDTO;
import com.carhub.domain.dto.CheckoutCreateDTO;
import com.carhub.domain.vo.CheckoutSnapshotVO;
import com.carhub.service.CheckoutService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

@Api(tags = "结算快照API")
@RestController
@RequestMapping("/api/checkout")
@RequiredArgsConstructor
@Validated
public class CheckoutController {

    private final CheckoutService checkoutService;

    @ApiOperation("创建结算快照（预下单）")
    @PostMapping
    public R<CheckoutSnapshotVO> createCheckout(@RequestBody(required = false) CheckoutCreateDTO dto) {
        if (dto == null) {
            dto = new CheckoutCreateDTO();
        }
        return R.ok(checkoutService.createCheckout(dto));
    }

    @ApiOperation("获取结算快照详情")
    @GetMapping("/{checkoutToken}")
    public R<CheckoutSnapshotVO> getCheckout(
            @ApiParam("结算Token") @PathVariable @NotBlank String checkoutToken) {
        return R.ok(checkoutService.getCheckout(checkoutToken));
    }

    @ApiOperation("确认结算（下单成功后调用）")
    @PostMapping("/confirm")
    public R<CheckoutSnapshotVO> confirmCheckout(@RequestBody @Valid CheckoutConfirmDTO dto) {
        return R.ok(checkoutService.confirmCheckout(dto));
    }

    @ApiOperation("取消结算")
    @PostMapping("/cancel/{checkoutToken}")
    public R<Boolean> cancelCheckout(
            @ApiParam("结算Token") @PathVariable @NotBlank String checkoutToken) {
        return R.ok(checkoutService.cancelCheckout(checkoutToken));
    }

    @ApiOperation("续期结算（延长有效期）")
    @PostMapping("/refresh/{checkoutToken}")
    public R<CheckoutSnapshotVO> refreshCheckout(
            @ApiParam("结算Token") @PathVariable @NotBlank String checkoutToken) {
        return R.ok(checkoutService.refreshCheckout(checkoutToken));
    }

    @ApiOperation("查询我的结算列表")
    @GetMapping("/list")
    public R<List<CheckoutSnapshotVO>> listMyCheckouts(
            @ApiParam("状态:0-待确认,1-已下单,2-已取消,3-已过期")
            @RequestParam(required = false) Integer status) {
        return R.ok(checkoutService.listMyCheckouts(status));
    }

}

package com.carhub.controller;

import com.carhub.common.result.R;
import com.carhub.domain.dto.CreateSnapshotDTO;
import com.carhub.domain.dto.RestoreSnapshotDTO;
import com.carhub.domain.vo.CartSnapshotVO;
import com.carhub.domain.vo.CartVO;
import com.carhub.service.CartSnapshotService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.List;

@Api(tags = "购物车历史版本/快照API")
@RestController
@RequestMapping("/api/cart/snapshot")
@RequiredArgsConstructor
@Validated
public class CartSnapshotController {

    private final CartSnapshotService cartSnapshotService;

    @ApiOperation("查询历史快照列表")
    @GetMapping("/list")
    public R<List<CartSnapshotVO>> getSnapshotHistory(
            @ApiParam("返回数量限制，默认100") @RequestParam(required = false) Integer limit) {
        return R.ok(cartSnapshotService.getSnapshotHistory(limit));
    }

    @ApiOperation("查询单个快照详情")
    @GetMapping("/{snapshotId}")
    public R<CartSnapshotVO> getSnapshotDetail(
            @ApiParam("快照ID") @PathVariable @NotBlank String snapshotId) {
        return R.ok(cartSnapshotService.getSnapshotDetail(snapshotId));
    }

    @ApiOperation("手动创建快照")
    @PostMapping
    public R<CartSnapshotVO> createSnapshot(@RequestBody(required = false) @Valid CreateSnapshotDTO dto) {
        if (dto == null) {
            dto = new CreateSnapshotDTO();
        }
        return R.ok(cartSnapshotService.createManualSnapshot(dto));
    }

    @ApiOperation("还原快照")
    @PostMapping("/restore")
    public R<CartVO> restoreSnapshot(@RequestBody @Valid RestoreSnapshotDTO dto) {
        return R.ok(cartSnapshotService.restoreSnapshot(dto));
    }

    @ApiOperation("删除快照")
    @DeleteMapping("/{snapshotId}")
    public R<Boolean> deleteSnapshot(
            @ApiParam("快照ID") @PathVariable @NotBlank String snapshotId) {
        return R.ok(cartSnapshotService.deleteSnapshot(snapshotId));
    }
}

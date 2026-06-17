package com.carhub.controller;

import com.carhub.common.result.R;
import com.carhub.domain.dto.CartEventDTO;
import com.carhub.service.CartAnalyticsService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@Api(tags = "购物车埋点事件上报API")
@RestController
@RequestMapping("/api/track")
@RequiredArgsConstructor
@Validated
public class CartTrackController {

    private final CartAnalyticsService cartAnalyticsService;

    @ApiOperation("上报单个埋点事件")
    @PostMapping("/event")
    public R<Boolean> trackEvent(@RequestBody @Valid CartEventDTO event) {
        cartAnalyticsService.trackEvent(event);
        return R.ok(true);
    }

    @ApiOperation("批量上报埋点事件")
    @PostMapping("/events")
    public R<Boolean> trackEvents(@RequestBody List<CartEventDTO> events) {
        cartAnalyticsService.trackEventBatch(events);
        return R.ok(true);
    }

    @ApiOperation("获取埋点服务健康状态")
    @GetMapping("/health")
    public R<Map<String, Object>> getHealth() {
        return R.ok(Map.of(
                "status", "ok",
                "timestamp", System.currentTimeMillis()
        ));
    }
}

package com.carhub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carhub.common.context.CartContextHolder;
import com.carhub.domain.entity.BizConfigEntity;
import com.carhub.mapper.BizConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BizConfigService {

    private final BizConfigMapper bizConfigMapper;

    public BizConfigEntity getConfig(String tenantId, String bizType) {
        LambdaQueryWrapper<BizConfigEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizConfigEntity::getTenantId, tenantId)
                .eq(BizConfigEntity::getBizType, bizType)
                .eq(BizConfigEntity::getStatus, 1)
                .eq(BizConfigEntity::getDeleted, 0);
        return bizConfigMapper.selectOne(wrapper);
    }

    @Cacheable(value = "bizConfigList", key = "#tenantId")
    public Map<String, BizConfigEntity> getAllConfigs(String tenantId) {
        LambdaQueryWrapper<BizConfigEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizConfigEntity::getTenantId, tenantId)
                .eq(BizConfigEntity::getStatus, 1)
                .eq(BizConfigEntity::getDeleted, 0);
        List<BizConfigEntity> list = bizConfigMapper.selectList(wrapper);
        Map<String, BizConfigEntity> map = new HashMap<>();
        for (BizConfigEntity config : list) {
            map.put(config.getBizType(), config);
        }
        return map;
    }

    public List<BizConfigEntity> listAll() {
        String tenantId = CartContextHolder.getTenantId();
        LambdaQueryWrapper<BizConfigEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizConfigEntity::getTenantId, tenantId)
                .eq(BizConfigEntity::getDeleted, 0)
                .orderByDesc(BizConfigEntity::getCreateTime);
        return bizConfigMapper.selectList(wrapper);
    }

    public boolean createConfig(BizConfigEntity entity) {
        entity.setTenantId(CartContextHolder.getTenantId());
        return bizConfigMapper.insert(entity) > 0;
    }

    public boolean updateConfig(BizConfigEntity entity) {
        entity.setTenantId(CartContextHolder.getTenantId());
        return bizConfigMapper.updateById(entity) > 0;
    }

    public boolean deleteConfig(Long id) {
        return bizConfigMapper.deleteById(id) > 0;
    }

}

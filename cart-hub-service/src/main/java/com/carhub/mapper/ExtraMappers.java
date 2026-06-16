package com.carhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
interface CartSnapshotMapper extends BaseMapper<com.carhub.domain.entity.CartSnapshotEntity> {
}

@Mapper
interface CartDiscountMapper extends BaseMapper<com.carhub.domain.entity.CartDiscountEntity> {
}

@Mapper
interface CartHistoryMapper extends BaseMapper<com.carhub.domain.entity.CartHistoryEntity> {
}

@Mapper
interface CartStatisticsMapper extends BaseMapper<com.carhub.domain.entity.CartStatisticsEntity> {
}

@Mapper
interface TenantMapper extends BaseMapper<com.carhub.domain.entity.TenantEntity> {
}

@Mapper
public interface CouponTemplateMapper extends BaseMapper<com.carhub.domain.entity.CouponTemplateEntity> {
}

@Mapper
interface PromotionActivityMapper extends BaseMapper<com.carhub.domain.entity.PromotionActivityEntity> {
}

@Mapper
interface UserCouponMapper extends BaseMapper<com.carhub.domain.entity.UserCouponEntity> {
}

@Mapper
public interface SkuAssociationMapper extends BaseMapper<com.carhub.domain.entity.SkuAssociationEntity> {
}

@Mapper
interface UserCartProfileMapper extends BaseMapper<com.carhub.domain.entity.UserCartProfileEntity> {
}

package com.carhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carhub.domain.entity.CartSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CartSnapshotMapper extends BaseMapper<CartSnapshotEntity> {

    @Select("SELECT * FROM t_cart_snapshot WHERE snapshot_id = #{snapshotId} AND deleted = 0")
    CartSnapshotEntity findBySnapshotId(@Param("snapshotId") String snapshotId);

    @Select("SELECT * FROM t_cart_snapshot " +
            "WHERE tenant_id = #{tenantId} AND biz_type = #{bizType} AND user_id = #{userId} " +
            "AND DATE(create_time) = #{date} AND snapshot_type = 'auto' AND deleted = 0 " +
            "ORDER BY create_time DESC LIMIT 1")
    CartSnapshotEntity findLatestAutoByDate(@Param("tenantId") String tenantId,
                                            @Param("bizType") String bizType,
                                            @Param("userId") String userId,
                                            @Param("date") LocalDate date);

    @Select("SELECT * FROM t_cart_snapshot " +
            "WHERE tenant_id = #{tenantId} AND biz_type = #{bizType} AND user_id = #{userId} " +
            "AND deleted = 0 " +
            "ORDER BY create_time DESC LIMIT #{limit}")
    List<CartSnapshotEntity> findByUser(@Param("tenantId") String tenantId,
                                        @Param("bizType") String bizType,
                                        @Param("userId") String userId,
                                        @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM t_cart_snapshot " +
            "WHERE tenant_id = #{tenantId} AND biz_type = #{bizType} AND user_id = #{userId} " +
            "AND snapshot_type = #{snapshotType} AND deleted = 0")
    int countByUserAndType(@Param("tenantId") String tenantId,
                           @Param("bizType") String bizType,
                           @Param("userId") String userId,
                           @Param("snapshotType") String snapshotType);

    @Select("SELECT snapshot_id FROM t_cart_snapshot " +
            "WHERE expire_time < #{now} AND deleted = 0 LIMIT #{limit}")
    List<String> findExpiredSnapshotIds(@Param("now") LocalDateTime now, @Param("limit") int limit);
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

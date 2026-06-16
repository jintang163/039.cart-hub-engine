package com.carhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carhub.domain.entity.FavoriteItemEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FavoriteItemMapper extends BaseMapper<FavoriteItemEntity> {

    @Select("SELECT * FROM t_favorite_item " +
            "WHERE tenant_id = #{tenantId} AND biz_type = #{bizType} " +
            "AND user_id = #{userId} AND deleted = 0 " +
            "ORDER BY add_time DESC")
    List<FavoriteItemEntity> findByUser(@Param("tenantId") String tenantId,
                                       @Param("bizType") String bizType,
                                       @Param("userId") String userId);

    @Select("SELECT * FROM t_favorite_item " +
            "WHERE tenant_id = #{tenantId} AND biz_type = #{bizType} " +
            "AND user_id = #{userId} AND sku_id = #{skuId} AND deleted = 0")
    FavoriteItemEntity findBySku(@Param("tenantId") String tenantId,
                             @Param("bizType") String bizType,
                             @Param("userId") String userId,
                             @Param("skuId") String skuId);

    @Delete("<script>" +
            "DELETE FROM t_favorite_item " +
            "WHERE tenant_id = #{tenantId} AND biz_type = #{bizType} " +
            "AND user_id = #{userId} AND sku_id IN " +
            "<foreach item='skuId' collection='skuIds' open='(' separator=',' close=')'>" +
            "#{skuId}" +
            "</foreach>" +
            " AND deleted = 0" +
            "</script>")
    int deleteBySkus(@Param("tenantId") String tenantId,
                @Param("bizType") String bizType,
                @Param("userId") String userId,
                @Param("skuIds") List<String> skuIds);

}

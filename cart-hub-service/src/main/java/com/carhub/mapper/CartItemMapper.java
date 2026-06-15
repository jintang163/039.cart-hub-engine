package com.carhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carhub.domain.entity.CartItemEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CartItemMapper extends BaseMapper<CartItemEntity> {

    int batchUpsertItems(@Param("list") List<CartItemEntity> list);

    int batchDeleteBySkus(@Param("tenantId") String tenantId,
                          @Param("bizType") String bizType,
                          @Param("userId") String userId,
                          @Param("skuIds") List<String> skuIds);

}

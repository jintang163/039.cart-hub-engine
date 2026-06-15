package com.carhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carhub.domain.entity.CartEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CartMapper extends BaseMapper<CartEntity> {

    int upsertCart(CartEntity entity);

    List<CartEntity> selectCartsNeedSync(@Param("lastSyncTime") LocalDateTime lastSyncTime,
                                          @Param("limit") int limit);

}

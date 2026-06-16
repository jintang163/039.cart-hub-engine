package com.carhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carhub.domain.entity.CartShareEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CartShareMapper extends BaseMapper<CartShareEntity> {

    int incrementViewCount(@Param("shareId") String shareId);

    int incrementAcceptCount(@Param("shareId") String shareId);

    List<CartShareEntity> selectByOwner(@Param("tenantId") String tenantId,
                                         @Param("bizType") String bizType,
                                         @Param("ownerId") String ownerId);

    int deleteExpiredShares(@Param("expireTime") LocalDateTime expireTime);

}

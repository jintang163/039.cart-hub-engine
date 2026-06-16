package com.carhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carhub.domain.entity.CartItemArchiveEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CartItemArchiveMapper extends BaseMapper<CartItemArchiveEntity> {

    int batchInsert(@Param("list") List<CartItemArchiveEntity> list);

    int cleanExpiredArchives(@Param("beforeTime") LocalDateTime beforeTime,
                             @Param("limit") int limit);

}

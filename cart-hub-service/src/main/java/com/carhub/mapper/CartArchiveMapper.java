package com.carhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carhub.domain.entity.CartArchiveEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CartArchiveMapper extends BaseMapper<CartArchiveEntity> {

    int batchInsert(@Param("list") List<CartArchiveEntity> list);

    int cleanExpiredArchives(@Param("beforeTime") LocalDateTime beforeTime,
                             @Param("limit") int limit);

}

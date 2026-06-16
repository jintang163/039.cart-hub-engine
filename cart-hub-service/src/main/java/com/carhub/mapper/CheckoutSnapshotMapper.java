package com.carhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carhub.domain.entity.CheckoutSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CheckoutSnapshotMapper extends BaseMapper<CheckoutSnapshotEntity> {

    @Select("SELECT * FROM t_checkout_snapshot WHERE checkout_token = #{checkoutToken} AND deleted = 0")
    CheckoutSnapshotEntity findByToken(@Param("checkoutToken") String checkoutToken);

    @Update("UPDATE t_checkout_snapshot SET status = 3, update_time = NOW() " +
            "WHERE status = 0 AND expire_time < #{now} AND deleted = 0")
    int expireOutdatedSnapshots(@Param("now") LocalDateTime now);

    @Select("SELECT checkout_token FROM t_checkout_snapshot " +
            "WHERE status = 0 AND stock_status = 1 AND expire_time < #{now} AND deleted = 0 " +
            "LIMIT #{limit}")
    List<String> findExpiredTokensNeedRelease(@Param("now") LocalDateTime now, @Param("limit") int limit);

}

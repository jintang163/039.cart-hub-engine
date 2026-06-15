package com.carhub.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class MybatisMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        if (log.isDebugEnabled()) {
            log.debug("start insert fill ....");
        }
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "deleted", Integer.class, 0);
        this.strictInsertFill(metaObject, "version", Long.class, 0L);
        try {
            if (metaObject.hasGetter("viewCount")) {
                this.strictInsertFill(metaObject, "viewCount", Integer.class, 0);
            }
            if (metaObject.hasGetter("acceptCount")) {
                this.strictInsertFill(metaObject, "acceptCount", Integer.class, 0);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (log.isDebugEnabled()) {
            log.debug("start update fill ....");
        }
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }

}

package com.show.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import javax.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 数据库初始化器
 * 应用启动时自动检查并补全缺失的数据库列，避免因表结构变更导致查询报错
 */
@Component
public class DatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    @Autowired
    private DataSource dataSource;

    /**
     * 启动时执行数据库 migration
     * 依次尝试添加各新增功能所需的列，列已存在时静默忽略
     */
//    @PostConstruct
//    public void init() {
//        addColumn("ALTER TABLE system_monitor ADD COLUMN top_processes TEXT", "top_processes");
//        addColumn("ALTER TABLE system_monitor ADD COLUMN top_mem_processes TEXT", "top_mem_processes");
//    }
//
//    private void addColumn(String sql, String columnName) {
//        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
//            stmt.execute(sql);
//            log.info("数据库列 {} 添加成功", columnName);
//        } catch (Exception e) {
//            log.debug("数据库列 {} 已存在或无需添加: {}", columnName, e.getMessage());
//        }
//    }
}
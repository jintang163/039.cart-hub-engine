package com.carhub.config;

import com.clickhouse.jdbc.ClickHouseDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

@Slf4j
@Configuration
public class ClickHouseConfig {

    @Value("${clickhouse.url:jdbc:clickhouse://127.0.0.1:8123/cart_hub_analytics}")
    private String url;

    @Value("${clickhouse.username:default}")
    private String username;

    @Value("${clickhouse.password:}")
    private String password;

    @Value("${clickhouse.socket-timeout:30000}")
    private int socketTimeout;

    @Value("${clickhouse.connection-timeout:10000}")
    private int connectionTimeout;

    @Bean
    public ClickHouseDataSource clickHouseDataSource() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("socket_timeout", String.valueOf(socketTimeout));
        properties.setProperty("connection_timeout", String.valueOf(connectionTimeout));
        properties.setProperty("use_server_time_zone", "false");
        properties.setProperty("use_time_zone", "Asia/Shanghai");

        ClickHouseDataSource dataSource = new ClickHouseDataSource(url, properties);
        log.info("ClickHouse DataSource initialized: url={}", url);
        return dataSource;
    }

    public Connection getConnection() throws SQLException {
        return clickHouseDataSource().getConnection();
    }
}

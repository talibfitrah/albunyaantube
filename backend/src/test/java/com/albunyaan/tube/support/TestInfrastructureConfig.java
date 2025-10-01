package com.albunyaan.tube.support;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

@TestConfiguration
class TestInfrastructureConfig {

    private static final Logger logger = LoggerFactory.getLogger(TestInfrastructureConfig.class);

    private final EmbeddedPostgres postgres;
    private final RedisServer redisServer;
    private final int redisPort;

    TestInfrastructureConfig() {
        try {
            this.postgres = EmbeddedPostgres.builder().start();
            initializeDatabase();
        } catch (IOException | SQLException ex) {
            throw new IllegalStateException("Failed to start embedded PostgreSQL instance", ex);
        }

        try {
            this.redisPort = findAvailableTcpPort();
            this.redisServer = RedisServer
                .builder()
                .setting("bind 127.0.0.1")
                .port(this.redisPort)
                .build();
            this.redisServer.start();
        } catch (RuntimeException ex) {
            closeQuietly();
            throw new IllegalStateException("Failed to start embedded Redis instance", ex);
        }
    }

    @Bean(name = "dataSource")
    @Primary
    DataSource dataSource() {
        var dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgres.getJdbcUrl("albunyaan", "albunyaan"));
        dataSource.setUsername("albunyaan");
        dataSource.setPassword("changeme");
        dataSource.addDataSourceProperty("stringtype", "unspecified");
        return dataSource;
    }

    @Bean
    @Primary
    RedisConnectionFactory redisConnectionFactory() {
        var configuration = new RedisStandaloneConfiguration("127.0.0.1", redisPort);
        var factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    @Primary
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    private void initializeDatabase() throws SQLException {
        try (var connection = postgres.getPostgresDatabase().getConnection();
             var statement = connection.createStatement()) {
            try {
                statement.execute("CREATE ROLE albunyaan LOGIN PASSWORD 'changeme'");
            } catch (SQLException ex) {
                if (!"42710".equals(ex.getSQLState())) {
                    throw ex;
                }
                statement.execute("ALTER ROLE albunyaan WITH LOGIN PASSWORD 'changeme'");
            }
        }

        try (var connection = postgres.getPostgresDatabase().getConnection();
             var statement = connection.createStatement()) {
            try {
                statement.execute("CREATE DATABASE albunyaan OWNER albunyaan");
            } catch (SQLException ex) {
                if (!"42P04".equals(ex.getSQLState())) {
                    throw ex;
                }
                statement.execute("ALTER DATABASE albunyaan OWNER TO albunyaan");
            }
        }
    }

    @PreDestroy
    void shutdown() {
        redisServer.stop();
        closeQuietly();
    }

    private int findAvailableTcpPort() {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            closeQuietly();
            throw new IllegalStateException("Failed to find an available TCP port for embedded Redis", ex);
        }
    }

    private void closeQuietly() {
        try {
            postgres.close();
        } catch (IOException ex) {
            logger.warn("Failed to close embedded PostgreSQL instance cleanly", ex);
        }
    }
}

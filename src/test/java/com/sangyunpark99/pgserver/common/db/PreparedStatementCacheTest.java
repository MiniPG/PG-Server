package com.sangyunpark99.pgserver.common.db;

import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.conf.PropertySet;
import com.mysql.cj.jdbc.JdbcConnection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PreparedStatementCacheTest {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("MySQL 드라이버가 PreparedStatement의 SQL 분석 결과를 커넥션마다 캐시한다")
    void driverCachesParsedStatements() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            PropertySet properties = connection.unwrap(JdbcConnection.class).getPropertySet();

            assertThat(properties.getBooleanProperty(PropertyKey.cachePrepStmts).getValue()).isTrue();
            assertThat(properties.getIntegerProperty(PropertyKey.prepStmtCacheSize).getValue()).isEqualTo(250);
            assertThat(properties.getIntegerProperty(PropertyKey.prepStmtCacheSqlLimit).getValue()).isEqualTo(2048);
        }
    }
}

package com.threeam.support;

import com.threeam.usage.DbUsageLimiter;
import com.threeam.usage.UsageProperties;
import java.util.function.IntSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

// 실제 MySQL과 Connector/J 기본 설정으로 도는 통합 테스트의 공통 바탕.
// JDBC URL에 useAffectedRows 같은 플래그를 붙이지 않는다. 운영과 같은 드라이버 기본값에서 반환값의
// 뜻이 맞는지가 이 테스트들이 지키려는 것이라, 플래그를 바꾸면 검증 대상이 사라진다.
// 스키마 DDL이 저장소에 없어서 테이블은 실제 엔티티로 Hibernate가 만든다.
// 경합 테스트는 스레드마다 따로 커밋되는 트랜잭션이 필요하므로 테스트를 감싸는 롤백 트랜잭션을 끈다.
@Tag("integration")
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // 경합 스레드가 커넥션을 기다리며 줄 서면 동시에 부딪히지 않는다. 스레드 수보다 넉넉히 둔다.
        "spring.datasource.hikari.maximum-pool-size=24"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(MysqlIntegrationTest.Beans.class)
public abstract class MysqlIntegrationTest {

    protected static final int THREADS = 16;
    // 깨진 구현이 운 좋게 한 판 통과하는 일이 없게 같은 경합을 여러 판 돌린다.
    protected static final int ROUNDS = 10;

    // 테스트 클래스마다 컨테이너를 새로 띄우지 않게 JVM 하나에 하나만 둔다.
    @ServiceConnection
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));

    static {
        MYSQL.start();
    }

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    // @Modifying 쿼리는 트랜잭션 밖에서 못 돈다. 스레드마다 자기 트랜잭션을 열고 커밋하게 한다.
    protected int updateInTx(IntSupplier update) {
        Integer affected = new TransactionTemplate(transactionManager).execute(s -> update.getAsInt());
        return affected == null ? 0 : affected;
    }

    protected JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    // 모든 하위 클래스가 같은 구성을 쓰게 해 스프링 컨텍스트와 스키마 생성을 한 번으로 줄인다.
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(UsageProperties.class)
    @Import(DbUsageLimiter.class)
    static class Beans {
    }
}

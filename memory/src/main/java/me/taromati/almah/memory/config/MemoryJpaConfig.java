package me.taromati.almah.memory.config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "plugins.memory", name = "enabled", havingValue = "true")
@EnableJpaRepositories(
        basePackages = "me.taromati.almah.memory.db.repository",
        entityManagerFactoryRef = "memoryEntityManagerFactory",
        transactionManagerRef = "memoryTransactionManager"
)
public class MemoryJpaConfig {

    @Bean
    @ConfigurationProperties(prefix = "plugins.memory.datasource")
    public DataSourceProperties memoryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "plugins.memory.datasource.hikari")
    public DataSource memoryDataSource() {
        return memoryDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean memoryEntityManagerFactory(
            @Qualifier("memoryDataSource") DataSource dataSource) {
        dropVolatileTables(dataSource);

        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(dataSource);
        emf.setPackagesToScan("me.taromati.almah.memory.db.entity");
        emf.setPersistenceUnitName("memory");
        emf.setJpaVendorAdapter(jpaVendorAdapter());
        emf.setJpaPropertyMap(jpaProperties());
        return emf;
    }

    @Bean
    public PlatformTransactionManager memoryTransactionManager(
            @Qualifier("memoryEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean("memoryJdbcTemplate")
    public JdbcTemplate memoryJdbcTemplate(@Qualifier("memoryDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    private void dropVolatileTables(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS memory_chunks_fts");
        } catch (Exception e) {
            log.debug("[MemoryJpaConfig] DROP volatile tables skipped: {}", e.getMessage());
        }
    }

    private JpaVendorAdapter jpaVendorAdapter() {
        return new HibernateJpaVendorAdapter();
    }

    private Map<String, Object> jpaProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.hbm2ddl.auto", "update");
        props.put("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
        props.put("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        props.put("hibernate.show_sql", "false");
        return props;
    }
}

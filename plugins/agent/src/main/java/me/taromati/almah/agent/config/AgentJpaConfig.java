package me.taromati.almah.agent.config;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
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

@Configuration
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
@EnableJpaRepositories(
        basePackages = "me.taromati.almah.agent.db.repository",
        entityManagerFactoryRef = "agentEntityManagerFactory",
        transactionManagerRef = "agentTransactionManager"
)
public class AgentJpaConfig {

    @Bean
    @ConfigurationProperties(prefix = "plugins.agent.datasource")
    public DataSourceProperties agentDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "plugins.agent.datasource.hikari")
    public DataSource agentDataSource() {
        return agentDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean agentEntityManagerFactory(
            @Qualifier("agentDataSource") DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(dataSource);
        emf.setPackagesToScan("me.taromati.almah.agent.db.entity");
        emf.setPersistenceUnitName("agent");
        emf.setJpaVendorAdapter(jpaVendorAdapter());
        emf.setJpaPropertyMap(jpaProperties());
        return emf;
    }

    @Bean
    public PlatformTransactionManager agentTransactionManager(
            @Qualifier("agentEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean("agentJdbcTemplate")
    public JdbcTemplate agentJdbcTemplate(@Qualifier("agentDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
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

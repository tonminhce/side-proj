package vn.vnpt.checkout.infrastructure.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/** Story 2.5 — checkout depends on inventory module. The auto-configured JPA scans only
 *  the @SpringBootApplication's package (vn.vnpt.checkout), so inventory's @Entity types are
 *  not managed. Explicit EMF + repo registration covers both packages. */
@Configuration
@EnableJpaRepositories(basePackages = {"vn.vnpt.checkout", "vn.vnpt.inventory"})
@EnableTransactionManagement
public class CheckoutPersistenceConfig {

  @Bean
  LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) throws ClassNotFoundException {
    LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
    emf.setDataSource(dataSource);
    emf.setPackagesToScan("vn.vnpt.checkout", "vn.vnpt.inventory");
    emf.setPersistenceUnitName("checkout-pu");
    emf.setPersistenceProviderClass(
        (Class<? extends jakarta.persistence.spi.PersistenceProvider>) Class.forName("org.hibernate.jpa.HibernatePersistenceProvider"));
    return emf;
  }

  @Bean
  PlatformTransactionManager transactionManager(LocalContainerEntityManagerFactoryBean emf) {
    return new JpaTransactionManager(emf.getObject());
  }
}

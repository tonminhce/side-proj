package vn.vnpt.util.config.tenant;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.vnpt.util.component.tenant.DataSourceProperties;

@Configuration
public class DataSourceConfig {

  private final DataSourceProperties dataSourceProperties;

  public DataSourceConfig(DataSourceProperties dataSourceProperties) {
    this.dataSourceProperties = dataSourceProperties;
  }

  @Bean
  public DataSource dataSource() {
    System.out.println("Initializing TenantRoutingDataSource...");
    System.out.println("Available datasources: " + dataSourceProperties.getDataSources().keySet());
    vn.vnpt.util.config.tenant.TenantRoutingDataSource customDataSource =
        new TenantRoutingDataSource();
    customDataSource.setTargetDataSources(dataSourceProperties.getDataSources());
    Object defaultDS = dataSourceProperties.getDataSources().get("default");
    System.out.println("Default datasource found: " + (defaultDS != null));
    customDataSource.setDefaultTargetDataSource(defaultDS);
    return customDataSource;
  }
}

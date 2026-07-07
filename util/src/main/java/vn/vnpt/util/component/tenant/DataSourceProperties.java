package vn.vnpt.util.component.tenant;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.stereotype.Component;

@Getter
@ConfigurationProperties(prefix = "tenants")
public class DataSourceProperties {

  private Map<Object, Object> dataSources = new LinkedHashMap<>();
  private final Map<String, String> schemas = new LinkedHashMap<>();

  public void setDataSources(Map<String, Map<String, String>> datasources) {
    datasources.forEach(
        (key, value) -> {
          this.dataSources.put(key, convert(value));
          this.schemas.put(key, value.get("schema"));
        });
  }

  public String getCurrentSchema(String tenantId) {
    return this.schemas.get(tenantId);
  }

  public DataSource convert(Map<String, String> source) {
    return DataSourceBuilder.create()
        .url(source.get("jdbcUrl"))
        .driverClassName(source.get("driverClassName"))
        .username(source.get("username"))
        .password(source.get("password"))
        .build();
  }
}

package vn.vnpt.util.properties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@NoArgsConstructor
@Configuration
@ConfigurationProperties(prefix = "folder", ignoreUnknownFields = false)
public class FolderProperties {
  private Temp temp;
  private String folderTemp;
  private String folderTemplate;

  @Getter
  @Setter
  public static class Temp {
    private int timeToLive;
  }
}

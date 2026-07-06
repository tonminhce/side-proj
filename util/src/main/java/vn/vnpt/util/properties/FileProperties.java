package vn.vnpt.util.properties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Configuration
@ConfigurationProperties(prefix = "file", ignoreUnknownFields = false)
public class FileProperties {
  private String endPoint;
  private String accessKey;
  private String secretKey;
  private String bucketName;
  private String publicBucketName;
  private String dvcBucketName;
  private String subDirectory;
}

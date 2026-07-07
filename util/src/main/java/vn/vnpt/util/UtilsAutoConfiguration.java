package vn.vnpt.util;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import vn.vnpt.util.common.FileUtil;
import vn.vnpt.util.common.SnowflakeIdGenerator;
import vn.vnpt.util.common.TemplateExcelWriter;
import vn.vnpt.util.component.tenant.DataSourceProperties;
import vn.vnpt.util.properties.FileProperties;
import vn.vnpt.util.properties.FolderProperties;
import vn.vnpt.util.properties.TelegramProperties;
import vn.vnpt.util.telegram.TelegramBotAPIUtil;

@Slf4j
@RequiredArgsConstructor
@Configuration
@ComponentScan
@EnableConfigurationProperties({
  FileProperties.class,
  FolderProperties.class,
  TelegramProperties.class,
  DataSourceProperties.class
})
public class UtilsAutoConfiguration {

  private final FileProperties fileProperties;

  private final FolderProperties folderProperties;

  private final TelegramProperties telegramProperties;

  // Story 0.5: injected to register snowflake.worker.id.source gauge per ADR-22 / R-08.
  private final MeterRegistry meterRegistry;

  @Bean
  public FileUtil fileUtil() {
    return new FileUtil(
        folderProperties.getFolderTemp(),
        folderProperties.getFolderTemplate(),
        fileProperties.getEndPoint(),
        fileProperties.getAccessKey(),
        fileProperties.getSecretKey(),
        fileProperties.getBucketName(),
        fileProperties.getPublicBucketName(),
        fileProperties.getDvcBucketName(),
        fileProperties.getSubDirectory());
  }

  @Bean
  public SnowflakeIdGenerator snowflakeIdGenerator() {
    // May throw WorkerIdMissingException in non-dev profiles (ADR-22 / R-08); do NOT swallow.
    long workerId = SnowflakeIdGenerator.getWorkerIdFromPod();

    // Source label: 1=podname, 2=securerandom per OBSERVABILITY-RUNBOOK.md line 192.
    String podName = System.getenv("POD_NAME");
    boolean fromPod = podName != null && podName.matches(".*-(\\d+)$");
    String sourceLabel = fromPod ? "podname" : "securerandom";
    double sourceValue = fromPod ? 1.0 : 2.0;

    Gauge.builder("snowflake.worker.id.source", () -> sourceValue)
        .tag("source", sourceLabel)
        .description("Snowflake worker-id derivation source per ADR-22 (1=podname, 2=securerandom)")
        .register(meterRegistry);

    return new SnowflakeIdGenerator(workerId);
  }

  @Bean
  public TemplateExcelWriter templateExcelWriter() {
    return new TemplateExcelWriter();
  }

  @Bean
  public JavaMailSender javaMailSender() {
    return new JavaMailSenderImpl();
  }

  @Bean
  public TelegramBotAPIUtil telegramBotAPIUtil() {
    return new TelegramBotAPIUtil(telegramProperties);
  }
}

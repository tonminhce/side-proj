package vn.vnpt.util;

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
import vn.vnpt.util.properties.FileProperties;
import vn.vnpt.util.properties.FolderProperties;
import vn.vnpt.util.properties.TelegramProperties;
import vn.vnpt.util.telegram.TelegramBotAPIUtil;

@Slf4j
@RequiredArgsConstructor
@Configuration
@ComponentScan
@EnableConfigurationProperties({ FileProperties.class, FolderProperties.class, TelegramProperties.class })
public class UtilsAutoConfiguration {

    private final FileProperties fileProperties;

    private final FolderProperties folderProperties;

    private final TelegramProperties telegramProperties;

    @Bean
    public FileUtil fileUtil() {
        return new FileUtil(
                folderProperties.getFolderTemp(), folderProperties.getFolderTemplate(),
                fileProperties.getEndPoint(), fileProperties.getAccessKey(),
                fileProperties.getSecretKey(),
                fileProperties.getBucketName(),
                fileProperties.getPublicBucketName(),
                fileProperties.getDvcBucketName(),
                fileProperties.getSubDirectory());
    }

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        return new SnowflakeIdGenerator(SnowflakeIdGenerator.getWorkerIdFromPod());
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

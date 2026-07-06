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
@ConfigurationProperties(prefix = "telegram", ignoreUnknownFields = false)
public class TelegramProperties {
    private String errorBotToken;
    private String errorGroupChatId;
    private Boolean isSendError = true;
}

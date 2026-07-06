package vn.vnpt.util.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendDocument;
import com.pengrad.telegrambot.request.SendMessage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vn.vnpt.util.common.CommonUtil;
import vn.vnpt.util.common.SnowflakeIdGenerator;
import vn.vnpt.util.common.constant.CommonConstant;
import vn.vnpt.util.properties.TelegramProperties;

@Getter
@Component
@Slf4j
public class TelegramBotAPIUtil {
  private final TelegramProperties telegramProperties;
  private final String REQUEST_CATION = "Nội dung yêu cầu";
  private final String RESPONSE_CATION = "Nội dung trả về";

  public TelegramBotAPIUtil(TelegramProperties telegramProperties) {
    this.telegramProperties = telegramProperties;
  }

  public void sendMessageWithChatId(String text, ParseMode parseMode) {
    // Create your bot passing the token received from @BotFather
    TelegramBot bot = new TelegramBot(telegramProperties.getErrorBotToken());
    SendMessage sendMessage = new SendMessage(telegramProperties.getErrorGroupChatId(), text);
    sendMessage.parseMode(parseMode);
    // Send messages
    bot.execute(sendMessage);
  }

  public void sendDocument(File file, ParseMode parseMode, String caption) {
    // Create your bot passing the token received from @BotFather
    TelegramBot bot = new TelegramBot(telegramProperties.getErrorBotToken());
    SendDocument sendDocument = new SendDocument(telegramProperties.getErrorGroupChatId(), file);
    sendDocument.parseMode(parseMode);
    sendDocument.caption(caption);
    // Send messages
    bot.execute(sendDocument);
  }

  public void sendErrorMessage(ErrorMessageObj errorMessageObj) {
    try {
      // Lấy thông tin request body
      String reqBody = errorMessageObj.getRequestBody();
      if (reqBody == null || reqBody.isEmpty()) {
        if (errorMessageObj.getWrappedReq().getInputStream().readAllBytes().length > 0) {
          reqBody =
              new String(
                  errorMessageObj.getWrappedReq().getInputStream().readAllBytes(),
                  StandardCharsets.UTF_8);
        }
      }

      // Lấy thông tin response body
      String respBody;
      byte[] respBytes = errorMessageObj.getWrappedRes().getContentAsByteArray();
      if (respBytes.length > 0) {
        respBody = new String(respBytes, StandardCharsets.UTF_8);
      } else if (errorMessageObj.getExceptionCaught() != null) {
        respBody = CommonUtil.getStackTraceAsString(errorMessageObj.getExceptionCaught());
      } else {
        respBody = "";
      }

      // Soạn nội dung gửi telegram bot
      final StringBuilder stringBuilder = new StringBuilder("MESSAGE CODE: <strong>");
      Long code = SnowflakeIdGenerator.generateId();
      stringBuilder
          .append(code)
          .append("</strong>\nSERVICE: <strong>")
          .append(errorMessageObj.getService())
          .append("</strong>\nAPI: <strong>")
          .append(errorMessageObj.getRequestURI())
          .append("</strong>\nREQUEST FROM: <strong>")
          .append(errorMessageObj.getRemoteHost())
          .append("</strong>\nUSERNAME: <strong>")
          .append(errorMessageObj.getUsername())
          .append("</strong>\nTIME: <strong>")
          .append(Instant.now())
          .append("</strong>\nMETHOD: <strong>")
          .append(errorMessageObj.getMethod())
          .append("</strong>\nSTATUS CODE: <strong>")
          .append(errorMessageObj.getStatus())
          .append("</strong>");
      File tempRequestBodyFile =
          CommonUtil.getTemplateFile(
              CommonConstant.TELEGRAM_TEMPORARY_FOLDER + code + "_REQUEST_BODY_LOG.txt");
      Files.writeString(tempRequestBodyFile.toPath(), Objects.requireNonNull(reqBody));
      File tempResponseBodyFile =
          CommonUtil.getTemplateFile(
              CommonConstant.TELEGRAM_TEMPORARY_FOLDER + code + "_RESPONSE_BODY_LOG.txt");
      Files.writeString(tempResponseBodyFile.toPath(), respBody);

      // Gửi telegram bot file nội dung
      sendMessageWithChatId(stringBuilder.toString(), ParseMode.HTML);
      // Gửi telegram bot file request body
      if (!reqBody.isEmpty()) {
        sendDocument(tempRequestBodyFile, ParseMode.HTML, REQUEST_CATION);
      }
      // Gửi telegram bot file response body
      if (!respBody.isEmpty()) {
        sendDocument(tempResponseBodyFile, ParseMode.HTML, RESPONSE_CATION);
      }
      Files.delete(tempRequestBodyFile.toPath());
      Files.delete(tempResponseBodyFile.toPath());
    } catch (Exception e) {
      log.error("sending error message to telegram bot failed");
    }
  }
}

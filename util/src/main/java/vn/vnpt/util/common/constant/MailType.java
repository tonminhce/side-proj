package vn.vnpt.util.common.constant;

import java.text.MessageFormat;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

public enum MailType {
  APPROVE_USER_REGISTRATION(
      "HỒ SƠ THÔNG TIN ĐĂNG KÝ MÃ SỐ VÙNG TRỒNG", "thymeleaf-html/approve-user-registration"),
  REJECT_USER_REGISTRATION(
      "HỒ SƠ THÔNG TIN ĐĂNG KÝ MÃ SỐ VÙNG TRỒNG", "thymeleaf-html/reject-user-registration"),
  RESET_PASSWORD("KHÔI PHỤC MẬT KHẨU", "thymeleaf-html/reset-password"),
  APPROVE_USER_UPDATE("THÔNG BÁO PHÊ DUYỆT YÊU CẦU CẬP NHẬT", "thymeleaf-html/approve-user-update"),
  REJECT_USER_UPDATE("THÔNG BÁO PHÊ DUYỆT YÊU CẦU CẬP NHẬT", "thymeleaf-html/reject-user-update"),
  RESTORE_USER_REGISTRATION(
      "HỒ SƠ THÔNG TIN ĐĂNG KÝ MÃ SỐ VÙNG TRỒNG", "thymeleaf-html/restore-user-registration");

  @Getter private String subject;

  @Getter private String template;

  MailType(String subject, String template) {
    this.subject = subject;
    this.template = template;
  }

  /**
   * Replace the given params in the given content
   *
   * @param content
   * @param params
   * @return
   */
  public static String replaceParams(String content, Object... params) {
    if (StringUtils.isNotBlank(content) && params != null && params.length > 0) {
      int numOfParams = params.length;
      Object[] paramsNotNull = new Object[numOfParams];
      for (int i = 0; i < numOfParams; i++) {
        Object val = params[i];
        if (val == null) {
          val = StringUtils.EMPTY;
        }
        paramsNotNull[i] = val;
      }
      return MessageFormat.format(content, paramsNotNull);
    }
    return content;
  }
}

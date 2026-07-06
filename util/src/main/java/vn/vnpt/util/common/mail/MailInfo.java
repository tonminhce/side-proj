package vn.vnpt.util.common.mail;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.vnpt.util.annotation.SpecialSymbolConstraint;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MailInfo {

  @SpecialSymbolConstraint private String from;

  @SpecialSymbolConstraint private String[] to;

  @SpecialSymbolConstraint private String[] cc;

  @SpecialSymbolConstraint private String[] bcc;

  @SpecialSymbolConstraint private String subject;

  @SpecialSymbolConstraint private String content;

  private boolean isHTML;

  private List<MailAttachment> attachments;

  private Integer mailType;

  public MailInfo(String[] to, String subject, String content, boolean isHTML) {
    this.to = to;
    this.cc = null;
    this.bcc = null;
    this.from = null;
    this.subject = subject;
    this.content = content;
    this.isHTML = isHTML;
    this.attachments = null;
    this.mailType = null;
  }

  public MailInfo(String[] to, String[] cc, String subject, String content, boolean isHTML) {
    this.to = to;
    this.cc = cc;
    this.bcc = null;
    this.from = null;
    this.subject = subject;
    this.content = content;
    this.isHTML = isHTML;
    this.attachments = null;
    this.mailType = null;
  }

  public MailInfo(
      String[] to, String[] cc, String[] bcc, String subject, String content, boolean isHTML) {
    this.to = to;
    this.cc = cc;
    this.bcc = bcc;
    this.from = null;
    this.subject = subject;
    this.content = content;
    this.isHTML = isHTML;
    this.attachments = null;
    this.mailType = null;
  }
}

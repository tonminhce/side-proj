package vn.vnpt.util.common.mail;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendGridMailRequest {

  List<Map<String, List<Map<String, String>>>> personalizations;

  Map<String, String> from;

  String subject;

  List<Map<String, String>> content;

  List<Map<String, String>> attachments;
}

package vn.vnpt.util.common.constant;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

public class SendGridConstant {

    @Getter
    public enum DeliveryEvent {
        PROCESSED(	"processed", 	SendStatus.PENDING, 	"Message has been received and is ready to be delivered."),
        DROPPED(	"dropped", 		SendStatus.SEND_FAILED, "You may see the following drop reasons: Invalid SMTPAPI header, Spam Content (if Spam Checker app is enabled), Unsubscribed Address, Bounced Address, Spam Reporting Address, Invalid, Recipient List over Package Quota."),
        DELIVERED(	"delivered", 	SendStatus.SENT, 		"Message has been successfully delivered to the receiving server."),
        DEFERRED(	"deferred", 	SendStatus.SEND_FAILED, "Receiving server temporarily rejected the message."),
        BOUNCE(		"bounce", 		SendStatus.SEND_FAILED, "Receiving server could not or would not accept the message. If a recipient has previously unsubscribed from your emails, the message is bounced."),
        UNKNOWN(	"", 			SendStatus.UNKNOWN, 	"");

        private final String value;
        private final SendStatus status;
        private final String description;

        DeliveryEvent(String value, SendStatus status, String description) {
            this.value = value;
            this.status = status;
            this.description = description;
        }

        public static DeliveryEvent get(String value) {
            if (StringUtils.isBlank(value))
                return DeliveryEvent.UNKNOWN;
            for (DeliveryEvent event : DeliveryEvent.values())
                if (event.value.equals(value))
                    return event;
            return DeliveryEvent.UNKNOWN;
        }
    }

    @Getter
    public enum EngagementEvent {
        OPEN(				"open", 				SendStatus.READ, 	"Recipient has opened the HTML message. Open Tracking needs to be enabled for this type of event."),
        CLICK(				"click", 				SendStatus.READ, 	"Recipient clicked on a link within the message. Click Tracking needs to be enabled for this type of event."),
        SPAM_REPORT(		"spamreport", 			SendStatus.UNKNOWN, "Recipient marked message as spam."),
        UNSUBSCRIBE(		"unsubscribe", 			SendStatus.UNKNOWN, "Recipient clicked on the ‘Opt Out of All Emails’ link (available after clicking the message’s subscription management link). Subscription Tracking needs to be enabled for this type of event."),
        GROUP_UNSUBSCRIBE(	"group_unsubscribe", 	SendStatus.UNKNOWN, "Recipient unsubscribed from a specific group either by clicking the link directly or updating their preferences. Subscription Tracking needs to be enabled for this type of event."),
        GROUP_RESUBSCRIBE(	"group_resubscribe", 	SendStatus.UNKNOWN, "Recipient resubscribed to a specific group by updating their preferences. Subscription Tracking needs to be enabled for this type of event."),
        UNKNOWN(			"", 					SendStatus.UNKNOWN, "");

        private final String value;
        private final SendStatus status;
        private final String description;

        EngagementEvent(String value, SendStatus status, String description) {
            this.value = value;
            this.status = status;
            this.description = description;
        }

        public static EngagementEvent get(String value) {
            if (StringUtils.isBlank(value))
                return EngagementEvent.UNKNOWN;
            for (EngagementEvent event : EngagementEvent.values())
                if (event.value.equals(value))
                    return event;
            return EngagementEvent.UNKNOWN;
        }
    }

    @Getter
    public enum SendStatus {
        PENDING(0),
        SENT(1),
        SEND_FAILED(2),
        READ(3),
        UNKNOWN(99);

        private final int value;

        SendStatus(int value) {
            this.value = value;
        }
    }

    @Getter
    public enum RequestSendStatus {
        CODE_200(200, "OK", "Your message is valid, but it is not queued to be delivered."),
        CODE_202(202, "ACCEPTED", "Your message is both valid, and queued to be delivered."),
        CODE_400(400, "BAD REQUEST", ""),
        CODE_401(401, "UNAUTHORIZED", "You do not have authorization to make the request."),
        CODE_403(403, "FORBIDDEN", ""),
        CODE_404(404, "NOT FOUND", "The resource you tried to locate could not be found or does not exist."),
        CODE_405(405, "METHOD NOT ALLOWED", ""),
        CODE_413(413, "PAYLOAD TOO LARGE", "The JSON payload you have included in your request is too large."),
        CODE_415(415, "UNSUPPORTED MEDIA TYPE", ""),
        CODE_429(429, "TOO MANY REQUESTS", "The number of requests you have made exceeds SendGrid’s rate limitations"),
        CODE_500(500, "SERVER UNAVAILABLE", "An error occurred on a SendGrid server."),
        CODE_503(503, "SERVICE NOT AVAILABLE", "The SendGrid v3 Web API is not available."),
        UNKNOWN(0, "", "");

        private final int code;
        private final String message;
        private final String description;

        RequestSendStatus(int code, String message, String description) {
            this.code = code;
            this.message = message;
            this.description = description;
        }

        public static RequestSendStatus getStatus(int code) {
            for (RequestSendStatus status : RequestSendStatus.values())
                if (code == status.code)
                    return status;
            return RequestSendStatus.UNKNOWN;
        }
    }
}
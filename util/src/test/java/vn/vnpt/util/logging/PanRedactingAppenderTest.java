package vn.vnpt.util.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;

/**
 * Integration test for {@link PanRedactingAppender} — verifies that a 13–19 digit PAN-shaped
 * message is redacted before reaching the delegate appender.
 *
 * <p>Story 3.3 / FR-29 / R-15 / ADR-23.
 */
class PanRedactingAppenderTest {

  static class CapturingAppender extends AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> {
    final List<String> messages = new ArrayList<>();
    @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
      messages.add(event.getFormattedMessage());
    }
  }

  @Test
  void redactsPanInMessageBeforeForwardingToDelegate() {
    CapturingAppender capture = new CapturingAppender();
    capture.start();

    PanRedactingAppender redactor = new PanRedactingAppender();
    @SuppressWarnings({"unchecked", "rawtypes"})
    Appender delegateRef = (Appender) capture;
    redactor.setDelegate(delegateRef);
    redactor.setContext((ch.qos.logback.core.Context) LoggerFactory.getILoggerFactory());
    redactor.start();

    Logger logger = (Logger) LoggerFactory.getLogger(PanRedactingAppenderTest.class);
    LoggingEvent event = new LoggingEvent(
        "test.source",
        logger,
        Level.INFO,
        "PAN=4111111111111111 captured",
        null, null);
    redactor.doAppend(event);

    assertThat(capture.messages).hasSize(1);
    assertThat(capture.messages.get(0)).contains("***REDACTED:PAN***");
    assertThat(capture.messages.get(0)).doesNotContain("4111111111111111");
  }

  @Test
  void forwardsNonPanMessageUnchanged() {
    CapturingAppender capture = new CapturingAppender();
    capture.start();

    PanRedactingAppender redactor = new PanRedactingAppender();
    @SuppressWarnings({"unchecked", "rawtypes"})
    Appender delegateRef = (Appender) capture;
    redactor.setDelegate(delegateRef);
    redactor.setContext((ch.qos.logback.core.Context) LoggerFactory.getILoggerFactory());
    redactor.start();

    Logger logger = (Logger) LoggerFactory.getLogger(PanRedactingAppenderTest.class);
    LoggingEvent event = new LoggingEvent(
        "test.source",
        logger,
        Level.INFO,
        "order=42 amount=1999",
        null, null);
    redactor.doAppend(event);

    assertThat(capture.messages).hasSize(1);
    assertThat(capture.messages.get(0)).isEqualTo("order=42 amount=1999");
  }
}
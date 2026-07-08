package vn.vnpt.util.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;

/**
 * Logback appender that redacts PAN-shaped fields ({@code \\d{13,19}}) from the rendered message
 * and the formatted-message string before forwarding to the delegate appender — Story 3.3 / FR-29
 * / R-15 / ADR-23.
 *
 * <p>Wire as the outermost appender in {@code logback-spring.xml}: replace the console appender's
 * {@code <appender class="...">} with a {@code <appender class="...PanRedactingAppender">} that
 * holds the real appender as a delegate. The original console / file appender is referenced via
 * {@code <appender-ref>} child element.
 *
 * <p>Stable marker {@code ***REDACTED:PAN***} lets reviewers grep for accidental PAN leaks.
 */
public class PanRedactingAppender extends AppenderBase<ILoggingEvent> {

  private Appender<ILoggingEvent> delegate;

  public void setDelegate(Appender<ILoggingEvent> delegate) {
    this.delegate = delegate;
  }

  @Override
  public void start() {
    if (delegate == null) {
      addError("PanRedactingAppender requires a delegate appender; none configured");
      return;
    }
    super.start();
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (delegate == null) {
      return;
    }
    String redactedMsg = PanRedactor.redact(event.getMessage());
    String redactedFormatted = PanRedactor.redact(event.getFormattedMessage());

    if (redactedMsg.equals(event.getMessage()) && redactedFormatted.equals(event.getFormattedMessage())) {
      delegate.doAppend(event);
      return;
    }

    ILoggingEvent rewritten = new RewrittenLoggingEvent(event, redactedMsg, redactedFormatted);
    delegate.doAppend(rewritten);
  }

  @Override
  public void stop() {
    super.stop();
    if (delegate != null) {
      delegate.stop();
    }
  }
}
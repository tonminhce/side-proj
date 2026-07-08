package vn.vnpt.util.logging;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextVO;

/**
 * Lightweight immutable {@link ILoggingEvent} that exposes redacted message + formatted-message
 * strings AND redacted MDC + KeyValuePairs (defense in depth — Story 3.3 / FR-29 / MEDIUM-2 fix).
 *
 * <p>ponytail: avoids touching the original event; the appender chain is forward-only, so a wrapper
 * is the smallest delta that keeps the original metadata (logger, level, timestamp, throwable).
 */
record RewrittenLoggingEvent(
    ILoggingEvent delegate,
    String redactedMessage,
    String redactedFormattedMessage) implements ILoggingEvent {

  @Override public String getThreadName() { return delegate.getThreadName(); }
  @Override public Level getLevel() { return delegate.getLevel(); }
  @Override public String getLoggerName() { return delegate.getLoggerName(); }
  @Override public String getMessage() { return redactedMessage; }
  @Override public String getFormattedMessage() { return redactedFormattedMessage; }
  @Override public Object[] getArgumentArray() { return delegate.getArgumentArray(); }
  @Override public LoggerContextVO getLoggerContextVO() { return delegate.getLoggerContextVO(); }
  @Override public IThrowableProxy getThrowableProxy() { return delegate.getThrowableProxy(); }
  @Override public StackTraceElement[] getCallerData() { return delegate.getCallerData(); }
  @Override public boolean hasCallerData() { return delegate.hasCallerData(); }
  @Override public Marker getMarker() { return delegate.getMarker(); }
  @Override public List<Marker> getMarkerList() { return delegate.getMarkerList(); }

  /** Redact every value in the MDC map (MEDIUM-2 fix — keys pass through). */
  @Override public Map<String, String> getMDCPropertyMap() {
    Map<String, String> original = delegate.getMDCPropertyMap();
    if (original == null || original.isEmpty()) {
      return original;
    }
    Map<String, String> redacted = new java.util.HashMap<>(original.size());
    original.forEach((k, v) -> redacted.put(k, PanRedactor.redact(v)));
    return redacted;
  }

  @Override public Map<String, String> getMdc() { return getMDCPropertyMap(); }

  /** Redact every value in structured KeyValuePairs (MEDIUM-2 fix — keys pass through). */
  @Override public List<KeyValuePair> getKeyValuePairs() {
    List<KeyValuePair> original = delegate.getKeyValuePairs();
    if (original == null || original.isEmpty()) {
      return original;
    }
    List<KeyValuePair> redacted = new java.util.ArrayList<>(original.size());
    for (KeyValuePair kvp : original) {
      Object safeValue = kvp.value instanceof String s ? PanRedactor.redact(s) : kvp.value;
      redacted.add(new KeyValuePair(kvp.key, safeValue));
    }
    return redacted;
  }

  @Override public long getTimeStamp() { return delegate.getTimeStamp(); }
  @Override public int getNanoseconds() { return delegate.getNanoseconds(); }
  @Override public Instant getInstant() { return delegate.getInstant(); }
  @Override public long getSequenceNumber() { return delegate.getSequenceNumber(); }
  @Override public void prepareForDeferredProcessing() { delegate.prepareForDeferredProcessing(); }
}
package vn.vnpt.util.common;

public class ScheduleContext {
  private static final ThreadLocal<Boolean> isSchedulerContext = new ThreadLocal<>();

  public static void setSchedulerContext(boolean value) {
    isSchedulerContext.set(value);
  }

  public static boolean isFromScheduler() {
    return Boolean.TRUE.equals(isSchedulerContext.get());
  }

  public static void clear() {
    isSchedulerContext.remove();
  }
}

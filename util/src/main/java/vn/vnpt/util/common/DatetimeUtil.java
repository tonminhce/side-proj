package vn.vnpt.util.common;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.beans.factory.annotation.Value;
import vn.vnpt.util.common.constant.ValidationConstant;
import vn.vnpt.util.exception.CustomException;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Calendar;
import java.util.Date;
import java.util.Optional;
import java.util.TimeZone;


@Slf4j
public class DatetimeUtil {

    /**
     * yyyy-MM-dd
     */
    public static final String DATE_FORMAT_3 = "yyyy-MM-dd";

    public static final String DATE_FORMAT_1 = "dd-MM-yyyy";

    public static final String DATE_FORMAT_5 = "dd/MM/yyyy";
    /**
     * yyyy/MM/dd
     */
    public static final String DATE_FORMAT_4 = "yyyy/MM/dd";

    /**
     * yyyy-MM-dd HH:mm:ss
     */
    public static final String DATE_TIME_FORMAT_3 = "dd/MM/yyyy HH:mm:ss";

    public static final String DATE_TIME_FORMAT_4 = "HH:mm:ss dd/MM/yyyy";
    public static final String DATE_TIME_FORMAT_5 = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
    public static final String DATE_TIME_FORMAT_6 = "yyyy-MM-dd HH:mm:ss.SSSSSSXXX";

    public static final ZoneId ZONE_UTC = ZoneId.of("UTC");


    @Value("${spring.jackson.time-zone}")
    private static final String timeZone = "Asia/Ho_Chi_Minh";

    public static final ZoneId zoneId = ZoneId.of(timeZone);

    public static String formatDateToString(Date date, String pattern) {
        if (null == date)
            return "";
        if (StringUtils.isBlank(pattern))
            pattern = DATE_FORMAT_3;
        return new SimpleDateFormat(pattern).format(date);
    }

    private DatetimeUtil() {
    }


    public static Date getCurrentDatetime() {
        return Calendar.getInstance().getTime();
    }

    public static LocalDate getCurrentLocalDate() {
        return LocalDate.now();
    }

    public static String getCurrentDatetimeInFormat(String format) {
        if (StringUtils.isBlank(format))
            format = DATE_FORMAT_3;
        DateFormat df = new SimpleDateFormat(format);
        df.setTimeZone(TimeZone.getTimeZone(timeZone));
        return df.format(getCurrentDatetime());
    }

    /**
     * Client cast missing time value will lead date losing 1 day
     *
     * @return Date without time
     */
    @Deprecated
    public static Date getCurrentDate() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.HOUR, 0);
        return cal.getTime();
    }

    public static int getCalendarField(int field) {
        Calendar cal = Calendar.getInstance();
        return cal.get(field);
    }

    public static int getYear(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return cal.get(Calendar.YEAR);
    }

    public static int getMonth(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return (cal.get(Calendar.MONTH) + 1);
    }

    public static Date parseStringToDateWithFormat(String dateString, String format) {
        try {
            DateFormat df = new SimpleDateFormat(format);
            Date resultDate = null;
            if (StringUtils.isNotBlank(dateString)) {
                resultDate = df.parse(dateString);
            }
            return resultDate;
        } catch (Exception e) {
            throw new CustomException(e.getMessage());
        }
    }

    public static Date dateFormat(Date date, String format) {
        try {
            SimpleDateFormat df = new SimpleDateFormat(format);
            Date resultDate = null;
            if (date != null) {
                resultDate = df.parse(df.format(date));
            }
            return resultDate;
        } catch (Exception e) {
            throw new CustomException(e.getMessage());
        }
    }

    public static boolean isValidDate(String input, String formatString) {
        try {
            SimpleDateFormat format = new SimpleDateFormat(formatString);
            format.setLenient(false);
            format.parse(input);
        } catch (ParseException | IllegalArgumentException ex) {
            log.debug("", ex);
            return false;
        }
        return true;
    }

    public static Date addDays(Date date, int days) {
        if (date == null) return null;
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(Calendar.DATE, days); // minus number would decrement the days
        return cal.getTime();
    }

    public static Date addMonths(Date date, int months) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(Calendar.MONTH, months); // minus number would decrement the months
        return cal.getTime();
    }

    public static int subDate(String dateFrom, String dateTo, String dateFormat) {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(dateFormat);
        int result = 0;
        LocalDate locateDateFrom = LocalDate.parse(dateFrom, dateTimeFormatter);
        LocalDate locateDateTo = LocalDate.parse(dateTo, dateTimeFormatter);
        Duration diff = Duration.between(locateDateFrom.atStartOfDay(), locateDateTo.atStartOfDay());
        result = (int) diff.toDays();
        return result;
    }

    public static int subDate(Date dateFrom, Date dateTo, String dateFormat) {
        String dateStringFrom = DatetimeUtil.formatDateToString(dateFrom, dateFormat);
        String dateStringTo = DatetimeUtil.formatDateToString(dateTo, dateFormat);
        return subDate(dateStringFrom, dateStringTo, dateFormat);
    }

    public static boolean isSameDate(Date date1, Date date2, boolean truncateTime) {
        if (date1 == null && date2 == null) {
            return true;
        } else if (date1 != null && date2 != null) {
            if (!truncateTime) {
                return DateUtils.isSameDay(date1, date2);
            } else {
                Calendar cal1 = Calendar.getInstance();
                cal1.setTime(date1);
                cal1.set(Calendar.HOUR, 0);
                cal1.set(Calendar.MINUTE, 0);
                cal1.set(Calendar.SECOND, 0);
                cal1.set(Calendar.MILLISECOND, 0);

                Calendar cal2 = Calendar.getInstance();
                cal2.setTime(date2);
                cal2.set(Calendar.HOUR, 0);
                cal2.set(Calendar.MINUTE, 0);
                cal2.set(Calendar.SECOND, 0);
                cal2.set(Calendar.MILLISECOND, 0);

                return DateUtils.isSameDay(cal1, cal2);
            }
        } else {
            return false;
        }
    }

    public static void validateYearFromString(String year) {
        if (!year.matches(ValidationConstant.YEAR)) {
            throw new CustomException("Năm chưa đúng định dạng");
        }
    }

    public static void validateYearMonthFromString(String yearMonth) {
        if (!yearMonth.matches(ValidationConstant.YEAR_MONTH)) {
            throw new CustomException("Tháng năm chưa đúng định dạng");
        }
    }

    public static LocalDate compareDateGetBefore(Optional<LocalDate> date1, Optional<LocalDate> date2) {
        return date1.isPresent() && date2.isPresent() && date1.get().compareTo(date2.get()) >= 0
                ? date2.get()
                : !date1.isPresent() && date2.isPresent()
                ? date2.get() : null;
    }

    public static LocalDate compareDateGetAfter(Optional<LocalDate> date1, Optional<LocalDate> date2) {
        return date1.isPresent() && date2.isPresent() && date1.get().compareTo(date2.get()) >= 0
                ? date1.get()
                : !date1.isPresent() && date2.isPresent()
                ? date2.get() : null;
    }

    public static LocalDate toDateByYearAndMonth(Integer year, Integer month) {
        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate lastDayOfMonth = yearMonth.atEndOfMonth();
        return LocalDate.of(year, month, lastDayOfMonth.getDayOfMonth());
    }

    public static LocalDate fromDateByYearAndMonth(Integer year, Integer month) {
        return LocalDate.of(year, month, 1);
    }

    public static String formatLocalDateTimeToPattern(LocalDateTime dateTime, String pattern, ZoneId zoneId) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
        return dateTime.atZone(zoneId).format(formatter);
    }

    public static LocalDateTime getFisrtDateOfYear(Year year) {
        return year.atDay(1).with(TemporalAdjusters.firstDayOfYear()).atStartOfDay();
    }

    public static LocalDate[] getQuarterBoundaries(int quarter, int year) {
        LocalDate firstDay, lastDay;

        switch (quarter) {
            case 1:
                firstDay = LocalDate.of(year, Month.JANUARY, 1);
                lastDay = LocalDate.of(year, Month.MARCH, 31);
                break;
            case 2:
                firstDay = LocalDate.of(year, Month.APRIL, 1);
                lastDay = LocalDate.of(year, Month.JUNE, 30);
                break;
            case 3:
                firstDay = LocalDate.of(year, Month.JULY, 1);
                lastDay = LocalDate.of(year, Month.SEPTEMBER, 30);
                break;
            case 4:
                firstDay = LocalDate.of(year, Month.OCTOBER, 1);
                lastDay = LocalDate.of(year, Month.DECEMBER, 31);
                break;
            default:
                throw new IllegalArgumentException("Invalid quarter: " + quarter);
        }

        return new LocalDate[]{firstDay, lastDay};
    }

    public static LocalDate parseThangNam(String namThang) { // yyyyMM
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMM");
        return LocalDate.parse(namThang + "01", DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    public static boolean isValidDate(int day, int month, int year) {
        try {
            // Tạo LocalDate từ thông tin ngày, tháng, năm
            LocalDate date = LocalDate.of(year, month, day);
            return true; // Nếu không ném lỗi, ngày hợp lệ
        } catch (DateTimeException e) {
            return false; // Nếu có lỗi, ngày không hợp lệ
        }
    }

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy");

    public static String formatLocalDate(LocalDate date) {
        if (date == null) return null;
        return DATE_FORMAT.format(Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()));
    }

    public static String formatTimestamp(Object timestamp) {
        if (timestamp == null) {
            return null;
        }
        try {
            String timestampStr = timestamp.toString().trim();
            LocalDateTime dateTime;
            if (timestampStr.contains("T")) {
                dateTime = LocalDateTime.parse(timestampStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } else if (timestampStr.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d")) {
                dateTime = LocalDateTime.parse(timestampStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S"));
            } else {
                dateTime = LocalDateTime.parse(timestampStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            return dateTime.format(DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy"));
        } catch (Exception e) {
            return timestamp.toString();
        }
    }

    public static LocalDateTime getCurrentLocalDateTime() {
        return LocalDateTime.now(ZoneId.of(timeZone));
    }

    public static LocalDateTime getVietNamLocalDateTime() {
        return LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    public static LocalDateTime getCurrentDateTime() {
        return LocalDateTime.now();
    }

    public static Instant getCurrentInstant() {
        return Instant.now();
    }

    public static LocalDate toLocalDate(LocalDateTime ldt) {
        return ldt != null ? ldt.toLocalDate() : null;
    }

    public static LocalDateTime startOfDay(LocalDate d) {
        return d == null ? null : d.atStartOfDay();
    }

    public static LocalDateTime endOfDay(LocalDate d) {
        return d == null ? null : d.atTime(LocalTime.MAX);
    }

    public static String localDateTimeToString(LocalDateTime dt) {
        return dt == null ? "" : dt.format(DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy"));
    }
}

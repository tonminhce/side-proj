package vn.vnpt.util.common;

import com.fasterxml.jackson.databind.util.StdDateFormat;

import java.text.FieldPosition;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;

public class JsonDateDeserializer extends StdDateFormat {
    /**
     * <code>serialVersionUID</code>
     */
    private static final long serialVersionUID = 1986983461908064560L;

    public static final String DEFAULT_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'";
    @Override
    public StringBuffer format(Date date, StringBuffer toAppendTo, FieldPosition fieldPosition) {
        SimpleDateFormat format = new SimpleDateFormat(DEFAULT_FORMAT);
        toAppendTo.append(format.format(date));
        return toAppendTo;
    }

    @Override
    public Date parse(String source, ParsePosition pos) {
        SimpleDateFormat format = new SimpleDateFormat(DEFAULT_FORMAT);
        try {
            return format.parse(source);
        } catch (ParseException var4) {
            return null;
        }
    }
}

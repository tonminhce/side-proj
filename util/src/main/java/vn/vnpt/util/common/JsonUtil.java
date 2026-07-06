package vn.vnpt.util.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import vn.vnpt.util.exception.CustomException;

import java.io.IOException;
import java.util.*;

@Slf4j
public class JsonUtil {
    public static String stringify(Object object) {
        String jsonString = "{}";
        ObjectMapper mapper = new ObjectMapper();
        try {
            jsonString = mapper.writeValueAsString(object);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return jsonString;
        }
        return jsonString;
    }

    public static String parseToJSON(Object objData) {
        String jsonString = "{}";
        ObjectMapper mapper = new ObjectMapper();
        try {
            jsonString = mapper.writeValueAsString(objData);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return jsonString;
        }
        return jsonString;
    }

    @SuppressWarnings("rawtypes")
    public static <T> List<T> jsonToList(String jsonData, Class<List> list, Class<T> clazz) {
        List<T> result = null;
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
        try {
            CollectionType listType = mapper.getTypeFactory().constructCollectionType(list, clazz);
            result = mapper.readValue(jsonData, listType);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        return result;
    }

    public static <T> T jsonToObject(String jsonData, Class<T> clazz) {
        try {
            T result = null;
            try {
                result = clazz.newInstance();
            } catch (InstantiationException | IllegalAccessException e1) {
                log.error(e1.getMessage(), e1);
            }

            ObjectMapper mapper = new ObjectMapper();
            try {
                result = mapper.readValue(jsonData, clazz);
            } catch (IOException e) {
                log.error(e.getMessage(), e);
            }
            return result;

        } catch (Exception e) {
            throw new CustomException(e.getMessage());
        }
    }

    public static Optional<Date> jsonToDate(Object jsonData, boolean forceCast) {
        try {
            if (jsonData != null) {
                Optional<Date> result;
                ObjectMapper mapper = new ObjectMapper();
                mapper.setDateFormat(new JsonDateDeserializer());
                String dataStr = jsonData.toString();
                try {
                    if (dataStr.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")) {
                        result = Optional.of(mapper.getDateFormat().parse(dataStr));
                    } else if (dataStr.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                        result = Optional.of(DatetimeUtil.parseStringToDateWithFormat(dataStr, "yyyy-MM-dd"));
                    } else if (dataStr.matches("^\\d{4}/\\d{2}/\\d{2}$")) {
                        result = Optional.of(DatetimeUtil.parseStringToDateWithFormat(dataStr, "yyyy/MM/dd"));
                    } else if (forceCast) { // conflict with normal number
                        if (dataStr.matches("^\\d{4}\\d{2}\\d{2}$")) {
                            result = Optional.of(DatetimeUtil.parseStringToDateWithFormat(dataStr, "yyyyMMdd"));
                        } else {
                            result = Optional.of(JsonUtil.jsonToObject(dataStr, Date.class));
                        }
                    } else {
                        result = Optional.empty();
                    }
                } catch (Exception e) {
                    log.error("Can not cast date " + jsonData, e.getMessage());
                    result = Optional.empty();
                }
                return result;
            } else {
                return Optional.empty();
            }
        } catch (Exception e) {
            throw new CustomException(e.getMessage());
        }
    }

    public static <T, K, V> List<T> listMapToListObject(List<Map<K, V>> listMap, Class<T> clazz) {
        String jsonData = parseToJSON(listMap);
        return jsonToList(jsonData, List.class, clazz);
    }

    public static <K, V> Map<K, V> jsonToMap(String jsonData) {
        Map<K, V> result = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        try {
            TypeReference<HashMap<K, V>> typeRef = new TypeReference<HashMap<K, V>>() {
            };
            result = mapper.readValue(jsonData, typeRef);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        return result;
    }

    public static <T> HttpEntity<T> createRequestEntity(T body, HttpHeaders httpHeaders) {
        return new HttpEntity<>(body, httpHeaders);
    }
}


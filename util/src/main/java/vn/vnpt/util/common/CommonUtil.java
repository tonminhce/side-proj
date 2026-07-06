package vn.vnpt.util.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vn.vnpt.util.exception.CustomException;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class CommonUtil {

    public static User getUserInfo() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            ModelMapper modelMapper = new ModelMapper();
            Map<String, Object> tokenAttributes = jwtAuthenticationToken.getTokenAttributes();
            return modelMapper.map(tokenAttributes, User.class);
        }
        return null;
    }

    public static String getLoggedAccountId() {
        User userInfo = getUserInfo();
        return userInfo != null && userInfo.getUuid() != null ? userInfo.getUuid().toString() : null;
    }

    /**
     * Lấy token JWT từ SecurityContext.
     *
     * @return token JWT dưới dạng String.
     */
    public static String getToken() {
        JwtAuthenticationToken jwtAuthenticationToken = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        if (jwtAuthenticationToken != null) {
            return jwtAuthenticationToken.getToken().getTokenValue();
        } else {
            throw new CustomException("Không tìm thấy thông tin xác thực trong SecurityContext.");
        }
    }

    /**
     * Lấy giá trị của header 'x-tenant' từ HttpServletRequest.
     *
     * @return giá trị của x-tenant nếu có, hoặc null nếu không có header này.
     */
    public static String getTenant() {
        HttpServletRequest request = ((ServletRequestAttributes) Objects.requireNonNull(RequestContextHolder.getRequestAttributes())).getRequest();
        return request.getHeader("x-tenant");  // Lấy giá trị của header 'x-tenant'
    }

    public static String getLoggedAccountName() {
        User userInfo = getUserInfo();
        return userInfo != null ? userInfo.getPreferred_username() : null;
    }

    /**
     * Handle duplicate entities and DTOs.
     * <p>
     * This method processes the given lists of entities and DTOs to identify duplicates and determine their status.
     * It returns a result containing two maps:
     * <p>
     * - `countDup`: a map that counts the occurrences of each key extracted from the entities and DTOs.
     * - count equal 1 => exists in entities but not in DTOs, indicating it should be deleted.
     * - count equal 0 => exists in both entities and DTOs, indicating it should be updated.
     * - count equal -1 => exists in DTOs but not in entities, indicating it should be added to the database.
     * - `dupEntity`: a map of the duplicate entities keyed by the extracted key.
     *
     * @param entities            the list of entities to be processed
     * @param dtos                the list of DTOs to be processed
     * @param keyExtractDupEntity function to extract the key from an entity for identifying duplicates
     * @param keyExtractDupDto    function to extract the key from a DTO for identifying duplicates
     * @param <T>                 the type of the entities
     * @param <U>                 the type of the DTOs
     * @param <V>                 the type of the key used for identifying duplicates
     * @return a result object containing the `countDup` map and the `dupEntity` map
     */
    public static <T, U, V> HandleDuplicateResult<T, U, V> handleDuplicate(List<T> entities, List<U> dtos,
                                                                           Function<T, V> keyExtractDupEntity,
                                                                           Function<U, V> keyExtractDupDto) {
        Map<V, Integer> countDup = new HashMap<>();
        Map<V, T> dupEntity = new HashMap<>();
        Map<V, U> dupDto = new HashMap<>();

        if (entities != null && !entities.isEmpty()) {
            // count occurrences in entities => init it to 1
            entities.forEach(entity -> {
                V key = keyExtractDupEntity.apply(entity);
                countDup.put(key, 1);
                dupEntity.put(key, entity);
            });
        }

        if (dtos != null && !dtos.isEmpty()) {
            // count occurrences in dtos => subtract if exist
            dtos.forEach(dto -> {
                V key = keyExtractDupDto.apply(dto);
                countDup.put(key, countDup.getOrDefault(key, 0) - 1);
                dupDto.put(key, dto);
            });
        }

        return new HandleDuplicateResult<>(countDup, dupEntity, dupDto);
    }

    public static <T, U> HandleDuplicateResult<T, U, U> handleDuplicate(List<T> entities, List<U> dtos,
                                                                        Function<T, U> keyExtractDupEntity) {
        Map<U, Integer> countDup = new HashMap<>();
        Map<U, T> dupEntity = new HashMap<>();
        Map<U, U> dupDto = new HashMap<>();

        if (entities != null && !entities.isEmpty()) {
            // count occurrences in entities => init it to 1
            entities.forEach(entity -> {
                U key = keyExtractDupEntity.apply(entity);
                countDup.put(key, 1);
                dupEntity.put(key, entity);
            });
        }

        if (dtos != null && !dtos.isEmpty()) {
            // count occurrences in dtos => subtract if exist
            dtos.forEach(key -> {
                countDup.put(key, countDup.getOrDefault(key, 0) - 1);
                dupDto.put(key, key);
            });
        }

        return new HandleDuplicateResult<>(countDup, dupEntity, dupDto);
    }

    public static <T, U> U modelMapper(T data, Class<U> aClass) {
        ModelMapper modelMapper = new ModelMapper();
        modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
        modelMapper.getConfiguration().setAmbiguityIgnored(true);

        if (data instanceof Map) {
            Map<String, Object> sourceMap = new HashMap<>((Map<String, Object>) data);
            for (Field field : aClass.getDeclaredFields()) {
                String fieldName = field.getName();
                if (sourceMap.containsKey(fieldName)) {
                    Object value = sourceMap.get(fieldName);
                    if (value instanceof String && field.getType().equals(LocalDate.class)) {
                        try {
                            sourceMap.put(fieldName, LocalDate.parse((String) value));
                        } catch (DateTimeParseException e) {
                            throw new CustomException("Không thể parse LocalDate cho field: " + fieldName);
                        }
                    }
                }
            }
            return modelMapper.map(sourceMap, aClass);
        }

        return modelMapper.map(data, aClass);
    }

    public static <T, U> List<U> modelMapperList(List<T> dataList, Class<U> aClass) {
        if (dataList == null || dataList.isEmpty()) {
            return Collections.emptyList();
        }
        return dataList.stream()
                .map(data -> modelMapper(data, aClass))
                .collect(Collectors.toList());
    }

    public static Map<String, Object> convertToMap(Object dto) {
        Map<String, Object> result = new HashMap<>();
        try {
            for (PropertyDescriptor propertyDescriptor :
                    java.beans.Introspector.getBeanInfo(dto.getClass(), Object.class)
                            .getPropertyDescriptors()) {
                String propertyName = propertyDescriptor.getName();
                Method getter = propertyDescriptor.getReadMethod();

                if (getter != null) {
                    Object value = getter.invoke(dto);
                    result.put(propertyName, value);
                }
            }
        } catch (IntrospectionException | ReflectiveOperationException e) {
            throw new RuntimeException("Failed to convert DTO to Map", e);
        }
        return result;
    }

    public static String getStackTraceAsString(Throwable ex) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        return sw.toString();
    }


    /**
     * Get template file by name
     *
     * @return File
     */
    public static File getTemplateFile(String fileName) {
        File tempFile = new File("/tmp/" + fileName);
        // Tạo thư mục nếu chưa tồn tại
        File parentDir = tempFile.getParentFile();
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }
        if (!parentDir.canWrite()) {
            throw new CustomException("Không có quyền ghi vào: " + parentDir.getAbsolutePath());
        }
        return tempFile;
    }

    public static String getLoggedUsername() {
        User userInfo = getUserInfo();
        return userInfo != null ? userInfo.getPreferred_username() : null;
    }
}


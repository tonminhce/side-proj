package vn.vnpt.util.common;

import org.apache.commons.lang3.RandomStringUtils;
import vn.vnpt.util.exception.CustomException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

public class StringUtil {
    public static String replacePercent(String input) {
        return Objects.nonNull(input) ? input.replaceAll("%", "\\\\%") : null;
    }

    public static String genUniqueText() {
        return String.format("%s", RandomStringUtils.randomAlphanumeric(8));
    }

    public static boolean isUnicodeString(final String value) {
        return !value.equals(new String(value.getBytes(ISO_8859_1), ISO_8859_1));
    }

    public static String generatePassword() {
        String lowercase = "abcdefghijklmnopqrstuvwxyz";
        String uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String digits = "0123456789";
        String specialChars = "!@#$%^&*";
        String allCharacters = lowercase + uppercase + digits + specialChars;

        SecureRandom secureRandom = new SecureRandom();

        // Tạo các ký tự còn lại cho mật khẩu
        StringBuilder password = new StringBuilder();

        // Đảm bảo có ít nhất một ký tự từ mỗi loại
        password.append(lowercase.charAt(secureRandom.nextInt(lowercase.length()))); // Chữ thường
        password.append(uppercase.charAt(secureRandom.nextInt(uppercase.length()))); // Chữ hoa
        password.append(digits.charAt(secureRandom.nextInt(digits.length()))); // Chữ số
        password.append(specialChars.charAt(secureRandom.nextInt(specialChars.length()))); // Ký tự đặc biệt

        // Bổ sung các ký tự ngẫu nhiên cho đến khi đủ độ dài
        for (int i = password.length(); i < 7; i++) {
            password.append(allCharacters.charAt(secureRandom.nextInt(allCharacters.length())));
        }

        // Trộn mật khẩu ngẫu nhiên hơn
        for (int i = 0; i < password.length(); i++) {
            int j = secureRandom.nextInt(password.length());
            char temp = password.charAt(i);
            password.setCharAt(i, password.charAt(j));
            password.setCharAt(j, temp);
        }

        // Tạo một ký tự đầu tiên là chữ hoa hoặc chữ thường
        char firstChar = (secureRandom.nextBoolean())
                ? lowercase.charAt(secureRandom.nextInt(lowercase.length()))
                : uppercase.charAt(secureRandom.nextInt(uppercase.length()));

        // Thêm ký tự đầu vào đầu mật khẩu đã trộn
        password.insert(0, firstChar);

        return password.toString();
    }

    public static <T> List<T> nullIfEmpty(List<T> list) {
        if (list == null) return null;
        if (list.isEmpty()) return null;
        // Kiểm tra nếu tất cả phần tử là null
        if (list.stream().allMatch(Objects::isNull)) return null;
        return list;
    }

    public static String nullIfEmpty(String value) {
        return (value == null || value.trim().isEmpty()) ? null : value;
    }

    public static String concatAddress(String addressDetail, String communeName, String provinceName) {
        List<String> parts = new ArrayList<>();
        if (addressDetail != null && !addressDetail.trim().isEmpty()) {
            parts.add(addressDetail.trim());
        }
        if (communeName != null && !communeName.trim().isEmpty()) {
            parts.add(communeName.trim());
        }
        if (provinceName != null && !provinceName.trim().isEmpty()) {
            parts.add(provinceName.trim());
        }
        return parts.isEmpty() ? "" : String.join(", ", parts);
    }

    public static Long parseToLong(Object value) throws Exception {
        switch (value) {
            case null -> {
                return null;
            }
            case Long l -> {
                return l;
            }
            case String stringValue -> {
                try {
                    return stringValue.isEmpty() ? null : Long.parseLong(stringValue);
                } catch (NumberFormatException e) {
                    throw new CustomException("Không thể parse chuỗi thành Long: " + stringValue);
                }
            }
            default -> {
            }
        }
        throw new CustomException("Giá trị không hợp lệ, phải là Long hoặc String: " + value.getClass().getName());
    }

    public static String normalize(String input) {
        if (input == null) {
            return null;
        }
        String result = input.trim();

        result = result.replaceAll("[^\\p{L}\\p{Nd}\\s]", "");

        result = result.replaceAll("\\s+", " ");

        return result;
    }

    public static String buildFullAddress(String address, String commune, String province) {
        List<String> parts = new ArrayList<>();
        if (address != null && !address.isBlank()) parts.add(address.trim());
        if (commune != null && !commune.isBlank()) parts.add(commune.trim());
        if (province != null && !province.isBlank()) parts.add(province.trim());
        return String.join(", ", parts);
    }


    public static String EscapeSpecialCharacterForSearch(String str) {
        if (str == null || str.isEmpty()) return "";
        Pattern SPECIAL_REGEX_CHARS = Pattern.compile("[%_'\"\\\\]"); // %, _, a single quote, a double quote, backslash
        return SPECIAL_REGEX_CHARS.matcher(str).replaceAll("\\\\$0");
    }

    /**
     * Xử lý chuỗi tìm kiếm cho câu lệnh LIKE SQL, escape các ký tự đặc biệt.
     * 
     * Nếu chuỗi input là null hoặc rỗng, trả về null.
     * Nếu không, tự động thêm ký tự escape (\) trước các ký tự đặc biệt: % và _
     * 
     * @param input Chuỗi đầu vào cần xử lý
     * @return Mẫu LIKE sau khi escape và bọc '%' hai đầu, hoặc null nếu input null/rỗng
     * 
     * Ví dụ:
     * - escapeLikeSearchPattern(null) -> null
     * - escapeLikeSearchPattern("") -> null
     * - escapeLikeSearchPattern("Nguyễn % Văn A") -> "%nguyễn \% văn a%"
     * - escapeLikeSearchPattern("Lê _ Hải") -> "%lê \_ hải%"
     * - escapeLikeSearchPattern("Trần Văn B") -> "%trần văn b%"
     */
    public static String escapeLikeSearchPattern(String input) {
        // Xử lý Null / Rỗng: trả về null nếu input là null hoặc chuỗi trống
        if (input == null || input.isEmpty()) {
            return null;
        }
        
        // Escape ký tự % và _ bằng dấu gạch chéo ngược (\)
        String escaped = input.trim()
                .replace("\\", "\\\\")  // Escape backslash trước
                .replace("%", "\\%")    // Escape %
                .replace("_", "\\_");   // Escape _

        return "%" + escaped.toLowerCase(Locale.ROOT) + "%";
    }

    // UserRegistrationInfoService

    public static String randomSuffix(int len) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(chars.charAt((int) (Math.random() * chars.length())));
        return sb.toString();
    }

    // Ép kiểu kết quả linh hoạt: true/false, "true"/"false", hoặc Map có key "exists"/"data"
    public static boolean coerceBoolean(Object rs) {
        if (rs == null) return false;
        if (rs instanceof Boolean b) return b;
        if (rs instanceof CharSequence cs) {
            String s = cs.toString().trim().toLowerCase();
            return s.equals("true") || s.equals("1") || s.equals("yes");
        }
        if (rs instanceof Map<?,?> m) {
            Object v = m.get("exists");
            if (v == null && m.get("data") instanceof Map<?,?> d) v = d.get("exists");
            if (v instanceof Boolean b) return b;
            if (v instanceof CharSequence cs) return "true".equalsIgnoreCase(cs.toString());
        }
        return false;
    }

    /**
     * Loại bỏ dấu tiếng Việt khỏi chuỗi, chuyển các ký tự có dấu thành không dấu
     * 
     * @param text Chuỗi tiếng Việt cần loại bỏ dấu
     * @return Chuỗi không dấu, hoặc null nếu input null
     */
    public static String removeVietnameseAccents(String text) {
        if (text == null) {
            return null;
        }

        String result = text;

        // Lowercase
        result = result.replace("à", "a").replace("á", "a").replace("ả", "a").replace("ã", "a").replace("ạ", "a");
        result = result.replace("ă", "a").replace("ằ", "a").replace("ắ", "a").replace("ẳ", "a").replace("ẵ", "a")
                .replace("ặ", "a");
        result = result.replace("â", "a").replace("ầ", "a").replace("ấ", "a").replace("ẩ", "a").replace("ẫ", "a")
                .replace("ậ", "a");
        result = result.replace("è", "e").replace("é", "e").replace("ẻ", "e").replace("ẽ", "e").replace("ẹ", "e");
        result = result.replace("ê", "e").replace("ề", "e").replace("ế", "e").replace("ể", "e").replace("ễ", "e")
                .replace("ệ", "e");
        result = result.replace("ì", "i").replace("í", "i").replace("ỉ", "i").replace("ĩ", "i").replace("ị", "i");
        result = result.replace("ò", "o").replace("ó", "o").replace("ỏ", "o").replace("õ", "o").replace("ọ", "o");
        result = result.replace("ô", "o").replace("ồ", "o").replace("ố", "o").replace("ổ", "o").replace("ỗ", "o")
                .replace("ộ", "o");
        result = result.replace("ơ", "o").replace("ờ", "o").replace("ớ", "o").replace("ở", "o").replace("ỡ", "o")
                .replace("ợ", "o");
        result = result.replace("ù", "u").replace("ú", "u").replace("ủ", "u").replace("ũ", "u").replace("ụ", "u");
        result = result.replace("ư", "u").replace("ừ", "u").replace("ứ", "u").replace("ử", "u").replace("ữ", "u")
                .replace("ự", "u");
        result = result.replace("ỳ", "y").replace("ý", "y").replace("ỷ", "y").replace("ỹ", "y").replace("ỵ", "y");
        result = result.replace("đ", "d");

        // Uppercase
        result = result.replace("À", "A").replace("Á", "A").replace("Ả", "A").replace("Ã", "A").replace("Ạ", "A");
        result = result.replace("Ă", "A").replace("Ằ", "A").replace("Ắ", "A").replace("Ẳ", "A").replace("Ẵ", "A")
                .replace("Ặ", "A");
        result = result.replace("Â", "A").replace("Ầ", "A").replace("Ấ", "A").replace("Ẩ", "A").replace("Ẫ", "A")
                .replace("Ậ", "A");
        result = result.replace("È", "E").replace("É", "E").replace("Ẻ", "E").replace("Ẽ", "E").replace("Ẹ", "E");
        result = result.replace("Ê", "E").replace("Ề", "E").replace("Ế", "E").replace("Ể", "E").replace("Ễ", "E")
                .replace("Ệ", "E");
        result = result.replace("Ì", "I").replace("Í", "I").replace("Ỉ", "I").replace("Ĩ", "I").replace("Ị", "I");
        result = result.replace("Ò", "O").replace("Ó", "O").replace("Ỏ", "O").replace("Õ", "O").replace("Ọ", "O");
        result = result.replace("Ô", "O").replace("Ồ", "O").replace("Ố", "O").replace("Ổ", "O").replace("Ỗ", "O")
                .replace("Ộ", "O");
        result = result.replace("Ơ", "O").replace("Ờ", "O").replace("Ớ", "O").replace("Ở", "O").replace("Ỡ", "O")
                .replace("Ợ", "O");
        result = result.replace("Ù", "U").replace("Ú", "U").replace("Ủ", "U").replace("Ũ", "U").replace("Ụ", "U");
        result = result.replace("Ư", "U").replace("Ừ", "U").replace("Ứ", "U").replace("Ử", "U").replace("Ữ", "U")
                .replace("Ự", "U");
        result = result.replace("Ỳ", "Y").replace("Ý", "Y").replace("Ỷ", "Y").replace("Ỹ", "Y").replace("Ỵ", "Y");
        result = result.replace("Đ", "D");

        return result;
    }

}

package vn.vnpt.util.component.converter;

import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.*;
import org.springframework.stereotype.Component;
import vn.vnpt.util.common.icode.AuthorityUtils;
import vn.vnpt.util.exception.CustomException;

@Component
public class PathConverter {
  private static final String CSV_JSON_FILE = "permissions/path_dictionary.csv";
  public static final Map<String, String> PATH_DICTIONARY = getDictionary();

  private static Map<String, String> getDictionary() {
    try {
      Map<String, String> pathDictionary = new HashMap<>();
      ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
      try (InputStream in = classLoader.getResourceAsStream(CSV_JSON_FILE);
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(Objects.requireNonNull(in)))) {
        try (CsvReader<CsvRecord> csv = CsvReader.builder().ofCsvRecord(reader)) {
          for (final CsvRecord csvRecord : csv) {
            pathDictionary.put(csvRecord.getField(0), csvRecord.getField(1));
          }
        }
      } catch (Exception e) {
        throw new Exception(
            Paths.get(Objects.requireNonNull(classLoader.getResource(CSV_JSON_FILE)).toURI())
                .toString());
      }
      return pathDictionary;
    } catch (Exception e) {
      throw new CustomException(e.getMessage());
    }
  }

  public boolean hasAuthority(String authority, Collection<String> source) {
    return AuthorityUtils.hasAuthority(authority, source)
        || AuthorityUtils.hasAuthority(authority, convertCodeToStringPermission(source));
  }

  public boolean hasAuthority(String[] authority, Collection<String> source) {
    return AuthorityUtils.hasAuthority(authority, source)
        || AuthorityUtils.hasAuthority(authority, convertCodeToStringPermission(source));
  }

  public String convertStringToCodePermission(String permission) {
    if (!Objects.isNull(permission)) {
      if (Objects.equals(permission, "/")) {
        return permission;
      } else {
        String[] arr = permission.split("/");
        String[] result = new String[arr.length];
        if (arr.length >= 2 && (arr[1].equals("aims") || arr[1].equals("green"))) {
          result[0] = "";
          for (int i = 1; i < arr.length; ++i) {
            String afterConvertString = getKeyByValue(PATH_DICTIONARY, arr[i]);
            if (!Objects.isNull(afterConvertString)) {
              result[i] = afterConvertString;
            } else {
              if (i == arr.length - 1 && arr[i].indexOf("--") == 0) {
                result[i] = arr[i];
              } else {
                return permission;
              }
            }
          }
          return String.join("/", result);
        } else {
          return permission;
        }
      }
    } else {
      return null;
    }
  }

  public String[] convertStringToCodePermission(String... permission) {
    if (!Objects.isNull(permission)) {
      String[] result = new String[permission.length];
      for (int i = 0; i < permission.length; ++i) {
        result[i] = convertStringToCodePermission(permission[i]);
      }
      return result;
    } else {
      return null;
    }
  }

  public String convertCodeToStringPermission(String permission) {
    if (!Objects.isNull(permission)) {
      if (Objects.equals(permission, "/")) {
        return permission;
      } else {
        String[] arr = permission.split("/");
        String[] result = new String[arr.length];
        if (arr.length >= 2 && (arr[1].equals("0") || arr[1].equals("SY"))) {
          result[0] = "";
          for (int i = 1; i < arr.length; ++i) {
            result[i] = PATH_DICTIONARY.getOrDefault(arr[i], arr[i]);
          }
          return String.join("/", result);
        } else {
          return permission;
        }
      }
    } else {
      return null;
    }
  }

  public Collection<String> convertCodeToStringPermission(Collection<String> permission) {
    if (!Objects.isNull(permission)) {
      Collection<String> result = new ArrayList<>();
      permission.forEach(el -> result.add(convertCodeToStringPermission(el)));
      return result;
    } else {
      return null;
    }
  }

  public static <T, E> T getKeyByValue(Map<T, E> map, E value) {
    for (Map.Entry<T, E> entry : map.entrySet()) {
      if (Objects.equals(value, entry.getValue())) {
        return entry.getKey();
      }
    }
    return null;
  }
}

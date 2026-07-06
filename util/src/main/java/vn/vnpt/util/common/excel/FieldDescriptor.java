package vn.vnpt.util.common.excel;

import java.util.function.BiFunction;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class FieldDescriptor<T> {
  BiFunction<T, Integer, Object> valueExtractor;
  String dataType;
  String style;

  public Object getValue(T obj, int index) {
    return valueExtractor.apply(obj, index);
  }
}

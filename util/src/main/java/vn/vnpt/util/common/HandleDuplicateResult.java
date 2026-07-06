package vn.vnpt.util.common;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HandleDuplicateResult<T, U, V> {
  private Map<V, Integer> countDup;
  private Map<V, T> dupEntity;
  private Map<V, U> dupDto;
}

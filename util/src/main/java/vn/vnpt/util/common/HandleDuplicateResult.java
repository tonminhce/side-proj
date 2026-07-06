package vn.vnpt.util.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HandleDuplicateResult<T, U, V> {
    private Map<V, Integer> countDup;
    private Map<V, T> dupEntity;
    private Map<V, U> dupDto;
}

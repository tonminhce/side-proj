package vn.vnpt.util.common.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.vnpt.util.common.PagingDto;

import java.util.List;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UnitParamWithArrayType extends PagingDto {
    List<Long> province;
    List<Long> commune;
    List<Long> hamlet;
    List<String> provinceCode;
    List<String> communeCode;

    public List<Long> getProvince() {
        return province == null || province.isEmpty() ? null : province;
    }

    public List<Long> getCommune() {
        return commune == null || commune.isEmpty() ? null : commune;
    }

    public List<Long> getHamlet() {
        return hamlet == null || hamlet.isEmpty() ? null : hamlet;
    }

    public List<String> getProvinceCode() {
        return provinceCode == null || provinceCode.isEmpty() ? null : provinceCode;
    }
}

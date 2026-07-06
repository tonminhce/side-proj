package vn.vnpt.util.common;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public abstract class PagingDto {
    @NotNull(message = "Số trang không được bỏ trống")
    protected Integer pageNum = 0;
    @NotNull(message = "Số dòng không được để trống.")
    protected Integer pageSize = 10;
    private String sortField;
    private String arrangementDirection = "DESC";
}

package vn.vnpt.util.exception;

import lombok.Getter;

import java.util.List;

/**
 * (vd cột A -> B -> A) trong cấu hình Dynamic Import.
 *
 * <p>Format message: {@code CIRCULAR_COLUMN_DEPENDENCY:<col1>-><col2>-><col1>}.
 * Khoá i18n {@code CIRCULAR_COLUMN_DEPENDENCY} dùng để FE/BE error handler dịch.
 */
@Getter
public class CircularColumnDependencyException extends RuntimeException {

    /** Danh sách columnCode tạo thành chu trình (lặp lại điểm đầu ở cuối). */
    private final List<String> cycle;

    public CircularColumnDependencyException(List<String> cycle) {
        // Format "CODE: Tiếng Việt — <path>" — CODE giữ EN cho FE map i18n;
        // phần sau dấu ":" là message tiếng Việt + cycle path để debug.
        super("CIRCULAR_COLUMN_DEPENDENCY: Phát hiện vòng lặp phụ thuộc cột — "
                + String.join("->", cycle));
        this.cycle = List.copyOf(cycle);
    }
}

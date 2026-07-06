package vn.vnpt.util.exception;

import lombok.Getter;

import java.util.List;

/**
 * trong cùng 1 File import (vd sheet1 -> sheet2 -> sheet1).
 *
 * <p>Cycle path lưu danh sách sheetNameInExcel (hoặc fallback "#<index>" nếu name null)
 * để FE hiển thị thân thiện cho admin.
 *
 * <p>Format message: {@code CIRCULAR_SHEET_LINK: Tiếng Việt — <path>}.
 * Khoá i18n {@code CIRCULAR_SHEET_LINK} dùng để FE/BE error handler dịch.
 */
@Getter
public class CircularSheetLinkException extends RuntimeException {

    /** Danh sách sheet tạo thành chu trình (lặp lại điểm đầu ở cuối để hiển thị rõ vòng). */
    private final List<String> cycle;

    public CircularSheetLinkException(List<String> cycle) {
        // Format "CODE: Tiếng Việt — <path>" — CODE giữ EN cho FE map i18n;
        // phần sau dấu ":" là message tiếng Việt + cycle path để debug.
        super("CIRCULAR_SHEET_LINK: Phát hiện vòng lặp liên kết sheet — "
                + String.join(" -> ", cycle));
        this.cycle = List.copyOf(cycle);
    }
}

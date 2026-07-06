package vn.vnpt.util.common.constant;

import lombok.Getter;

import java.util.Arrays;

public enum StatusEnum {
    STATUS_ENUM_31(31, "Đang soạn thảo"),
    STATUS_ENUM_32(32, "Đã gửi - Chưa xử lý"),
    STATUS_ENUM_33(33, "Đã từ chối"),
    STATUS_ENUM_34(34, "Đã cấp mã"),
    STATUS_ENUM_35(35, "Đình chỉ"),
    STATUS_ENUM_36(36, "Thu hồi");

    @Getter
    private final Integer statusId;

    @Getter
    private final String statusName;

    StatusEnum(Integer statusId, String statusName) {
        this.statusId = statusId;
        this.statusName = statusName;
    }

    public static String getStatusNameById(Integer statusId) {
        StatusEnum status = Arrays.stream(StatusEnum.values()).filter(statusEnum -> statusEnum.getStatusId().equals(statusId)).findFirst().orElse(null);
       return status == null ? null : status.getStatusName();
    }
}

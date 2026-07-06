package vn.vnpt.util.common;

import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class TimeDto {
    private Integer week;
    private LocalDate fromDate;
    private LocalDate toDate;
    private Integer year;
}

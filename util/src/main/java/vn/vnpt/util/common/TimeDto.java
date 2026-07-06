package vn.vnpt.util.common;

import java.time.LocalDate;
import lombok.*;

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

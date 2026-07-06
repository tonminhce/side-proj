package vn.vnpt.util.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import vn.vnpt.util.entity.DvcAttachment;

@Repository
public interface DvcAttachmentRepository
    extends JpaRepository<DvcAttachment, Long>, JpaSpecificationExecutor<DvcAttachment> {
  List<DvcAttachment> findByDvcAppUuid(Long dvcAppUuid);

  List<DvcAttachment> findByDvcAppUuidAndFileType(Long dvcAppUuid, String fileType);

  boolean existsByDvcIdGiayTo(String dvcIdGiayTo);
}

package vn.vnpt.util.component.softdelete.validator;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import vn.vnpt.util.component.softdelete.model.UkDescriptor;
import vn.vnpt.util.component.softdelete.registry.SoftDeleteMetadataRegistry;
import vn.vnpt.util.exception.InvalidInputException;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kiểm tra mọi nhóm {@code @SoftUk} trên entity không trùng với một bản ghi còn sống khác
 * ({@code is_deleted = false}). Gọi ở tầng service, ngay trước {@code repository.save(entity)}:
 *
 * <pre>{@code
 * ukValidator.validate(entity);
 * repository.save(entity);
 * }</pre>
 *
 * <p>Khi update, bản ghi đang sửa được loại khỏi kiểm tra bằng field {@code @Id} của entity
 * (project này là {@code uuid}) — nhờ vậy save không đổi giá trị UK không bị báo trùng nhầm
 * với chính nó.
 *
 * <p>Nhóm UK có field giá trị {@code null} sẽ được bỏ qua (null không tham gia so trùng).
 * Mọi vi phạm được gom lại trước khi throw để trả về đầy đủ lỗi field trong một lần,
 * dùng lại {@link InvalidInputException} sẵn có của project (đã được
 * {@code ApiExceptionHandle} xử lý → HTTP 400 kèm map lỗi theo field).
 */
@Component
@ConditionalOnBean(EntityManagerFactory.class)
@Slf4j
public class UkValidator {

    /** Tên metric khi phát hiện vi phạm UK. Tag: {@code entity}, {@code uk_name}. */
    public static final String METRIC_UK_VIOLATION = "softdelete.uk.violation";

    private final SoftDeleteMetadataRegistry registry;

    /**
     * MeterRegistry tùy chọn (qua {@link ObjectProvider}) — validator vẫn dùng được khi
     * actuator/metrics chưa wire (ví dụ test context), không bắt buộc phải có.
     */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    @PersistenceContext
    private EntityManager em;

    public UkValidator(SoftDeleteMetadataRegistry registry,
                        ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.registry = registry;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /**
     * @param entity entity cần kiểm tra (instance đã gán đủ giá trị field UK); no-op nếu null
     * @throws InvalidInputException nếu một hoặc nhiều nhóm @SoftUk trùng với bản ghi live khác
     */
    public void validate(Object entity) {
        if (entity == null) {
            return;
        }

        List<UkDescriptor> uks = registry.uksOf(entity.getClass());
        if (uks.isEmpty()) {
            return;
        }

        Object selfId = extractId(entity);
        Map<String, String> errors = new LinkedHashMap<>();

        for (UkDescriptor uk : uks) {
            List<Object> values = new ArrayList<>(uk.javaFields().size());
            boolean anyNull = false;
            for (Field f : uk.javaFields()) {
                Object v = readField(f, entity);
                if (v == null) {
                    anyNull = true;
                    break;
                }
                values.add(v);
            }
            if (anyNull) {
                continue;
            }

            if (conflictExists(uk, values, selfId)) {
                log.debug("[SoftDelete] UK violation trên {}: group='{}' — đã tồn tại bản ghi live trùng",
                        entity.getClass().getSimpleName(), uk.name());

                incrementUkViolationCounter(entity.getClass().getSimpleName(), uk.name());

                String msg = "Giá trị đã tồn tại (vi phạm khóa duy nhất '" + uk.name() + "')";
                for (Field f : uk.javaFields()) {
                    errors.put(f.getName(), msg);
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Query bằng Criteria builder thay vì JPQL/native SQL: tham chiếu theo field Java
     * ({@code root.get(fieldName)}) nên đổi tên field bắt lỗi lúc build/compile thay vì
     * runtime; giá trị bind qua {@code cb.equal} nên không có injection surface.
     *
     * <p>{@code FlushModeType.COMMIT} để Hibernate không tự flush entity dirty (đang update)
     * trước khi query — nếu để auto-flush, UPDATE sẽ chạm unique index DB trước và quăng
     * {@code DataIntegrityViolationException} thô thay vì lỗi 400 rõ field như mong muốn.
     */
    private boolean conflictExists(UkDescriptor uk, List<Object> values, Object selfId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Integer> cq = cb.createQuery(Integer.class);
        Root<?> root = cq.from(uk.entity());

        List<Predicate> predicates = new ArrayList<>(uk.javaFields().size() + 2);
        for (int i = 0; i < uk.javaFields().size(); i++) {
            predicates.add(cb.equal(root.get(uk.javaFields().get(i).getName()), values.get(i)));
        }
        // BaseEntity/RootEntity của project này KHÔNG có @SQLRestriction tự động lọc
        // is_deleted = false, nên predicate này là bắt buộc, không phải defense-in-depth.
        predicates.add(cb.isFalse(root.get("isDeleted")));

        Field idField = registry.idFieldOf(uk.entity());
        if (selfId != null && idField != null) {
            predicates.add(cb.notEqual(root.get(idField.getName()), selfId));
        }

        cq.select(cb.literal(1)).where(cb.and(predicates.toArray(new Predicate[0])));

        TypedQuery<Integer> q = em.createQuery(cq);
        q.setFlushMode(FlushModeType.COMMIT);
        q.setMaxResults(1);
        return !q.getResultList().isEmpty();
    }

    /**
     * Ghi metric best-effort — lỗi ở pipeline metric không bao giờ được làm fail request.
     */
    private void incrementUkViolationCounter(String entitySimpleName, String ukName) {
        try {
            MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
            if (meterRegistry == null) {
                return;
            }
            meterRegistry.counter(METRIC_UK_VIOLATION,
                    "entity", entitySimpleName,
                    "uk_name", ukName).increment();
        } catch (RuntimeException ex) {
            log.debug("[SoftDelete] Không ghi được metric UK violation (entity={}, uk={}): {}",
                    entitySimpleName, ukName, ex.toString());
        }
    }

    private static Object readField(Field f, Object target) {
        try {
            return f.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Không đọc được field UK " + f.getName(), e);
        }
    }

    /**
     * Lấy giá trị PK hiện tại của entity (qua field mang {@code @Id}, tra trong registry —
     * không hardcode tên field vì PK trong project này tên là {@code uuid}, không phải
     * {@code id}). Trả về {@code null} cho flow create (entity chưa có PK) hoặc entity không
     * có trong metamodel.
     */
    private Object extractId(Object entity) {
        Field idField = registry.idFieldOf(entity.getClass());
        if (idField == null) {
            return null;
        }
        try {
            return idField.get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Không đọc được id trên " + entity.getClass(), e);
        }
    }
}

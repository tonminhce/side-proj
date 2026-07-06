package vn.vnpt.util.component.softdelete.registry;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import vn.vnpt.util.common.entity.base.SoftDeletable;
import vn.vnpt.util.component.softdelete.annotation.SoftUk;
import vn.vnpt.util.component.softdelete.annotation.SoftUks;
import vn.vnpt.util.component.softdelete.model.UkDescriptor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Nguồn dữ liệu duy nhất cho metadata {@code @SoftUk}, build 1 lần lúc app start bằng cách
 * quét {@link EntityManagerFactory#getMetamodel()} — chỉ những entity Hibernate thực sự
 * đăng ký mới được index, không cần classpath scanning riêng.
 *
 * <p>Đồng thời cache field {@code @Id} của mỗi entity (PK trong project này là {@code uuid}
 * kiểu {@code Long} khai báo ở {@code BaseEntity}, nhưng registry tra theo annotation
 * {@code @Id} thay vì hardcode tên field, để không phụ thuộc convention đặt tên).
 */
@Slf4j
@Component
@ConditionalOnBean(EntityManagerFactory.class)
@RequiredArgsConstructor
public class SoftDeleteMetadataRegistry {

    private final EntityManagerFactory entityManagerFactory;

    /** entity class → danh sách nhóm UK khai báo trên entity đó */
    private volatile Map<Class<?>, List<UkDescriptor>> uksByEntity;

    /** entity class → field mang annotation {@code @Id} (đã setAccessible) */
    private volatile Map<Class<?>, Field> idFieldByEntity;

    @PostConstruct
    void build() {
        Map<Class<?>, List<UkDescriptor>> byEntityUk = new HashMap<>();
        Map<Class<?>, Field> idFields = new HashMap<>();

        for (EntityType<?> et : entityManagerFactory.getMetamodel().getEntities()) {
            Class<?> entity = et.getJavaType();

            Field idField = findIdField(entity);
            if (idField != null) {
                idField.setAccessible(true);
                idFields.put(entity, idField);
            }

            SoftUk[] uks = extractUks(entity);
            if (uks.length == 0) {
                continue;
            }

            requireSoftDeletableEntity(entity);

            String table = resolveTableName(entity);
            List<UkDescriptor> list = new ArrayList<>(uks.length);

            for (SoftUk uk : uks) {
                if (uk.fields().length == 0) {
                    throw new IllegalStateException(
                            "@SoftUk trên " + entity.getName() + " name='" + uk.name() + "' có fields() rỗng");
                }
                if (uk.columns().length != 0 && uk.columns().length != uk.fields().length) {
                    throw new IllegalStateException(
                            "@SoftUk trên " + entity.getName() + " name='" + uk.name()
                                    + "': columns().length phải bằng fields().length");
                }

                List<Field> javaFields = new ArrayList<>(uk.fields().length);
                List<String> columns = new ArrayList<>(uk.fields().length);
                for (int i = 0; i < uk.fields().length; i++) {
                    String fName = uk.fields()[i];
                    Field f = findField(entity, fName);
                    if (f == null) {
                        throw new IllegalStateException(
                                "@SoftUk trên " + entity.getName() + " tham chiếu field không tồn tại '" + fName + "'");
                    }
                    f.setAccessible(true);
                    javaFields.add(f);
                    columns.add((uk.columns().length > 0) ? uk.columns()[i] : camelToSnake(fName));
                }
                list.add(new UkDescriptor(entity, table, uk.name(), javaFields, columns));
            }

            byEntityUk.put(entity, list);
        }

        byEntityUk.replaceAll((k, v) -> List.copyOf(v));
        this.uksByEntity = Collections.unmodifiableMap(byEntityUk);
        this.idFieldByEntity = Collections.unmodifiableMap(idFields);

        int totalUks = this.uksByEntity.values().stream().mapToInt(List::size).sum();
        log.info("[SoftDelete] UK registry built: {} nhóm trên {} entity", totalUks, this.uksByEntity.size());
    }

    /**
     * @return danh sách (immutable) {@link UkDescriptor} khai báo trên entity; rỗng nếu không có
     */
    public List<UkDescriptor> uksOf(Class<?> entity) {
        return uksByEntity.getOrDefault(entity, List.of());
    }

    /**
     * @return field mang {@code @Id} của entity (đã setAccessible), hoặc {@code null} nếu
     * entity không có trong metamodel / không tìm thấy field
     */
    public Field idFieldOf(Class<?> entity) {
        return idFieldByEntity.get(entity);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void requireSoftDeletableEntity(Class<?> entityClass) {
        if (SoftDeletable.class.isAssignableFrom(entityClass)) {
            return;
        }
        throw new IllegalStateException(
                "[SoftDelete] @SoftUk trên " + entityClass.getName()
                        + " — entity phải implement SoftDeletable (có cột is_deleted), "
                        + "ví dụ kế thừa RootEntity/BaseEntity.");
    }

    private static String resolveTableName(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        if (table != null && !table.name().isBlank()) {
            return table.name();
        }
        return entityClass.getSimpleName().toLowerCase();
    }

    private static SoftUk[] extractUks(Class<?> entity) {
        SoftUks container = entity.getAnnotation(SoftUks.class);
        if (container != null) {
            return container.value();
        }
        SoftUk single = entity.getAnnotation(SoftUk.class);
        return (single != null) ? new SoftUk[] { single } : new SoftUk[0];
    }

    private static Field findField(Class<?> entity, String name) {
        for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // tiếp tục tìm ở superclass (MappedSuperclass)
            }
        }
        return null;
    }

    private static Field findIdField(Class<?> entity) {
        for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(Id.class)) {
                    return f;
                }
            }
        }
        return null;
    }

    private static String camelToSnake(String s) {
        StringBuilder out = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}

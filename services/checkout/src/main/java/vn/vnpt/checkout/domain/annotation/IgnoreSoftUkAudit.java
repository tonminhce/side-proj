package vn.vnpt.checkout.domain.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker for entities that legitimately lack {@code @SoftUk} — Story 2.3 (mirrors cart's
 * {@code vn.vnpt.cart.domain.annotation.IgnoreSoftUkAudit}).
 *
 * <p>The ArchUnit rule {@code checkout_softDeletableEntitiesHaveSoftUkAnnotation} requires every JPA
 * entity extending {@code RootEntity} to carry a {@code @SoftUk} (or {@code @SoftUks}) annotation,
 * otherwise the build fails. Entities that follow a finite-state-machine or terminal-only philosophy
 * do NOT need a soft-uniqueness invariant — this marker is the explicit opt-out.
 *
 * <p>Every use of this annotation MUST include a JavaDoc justification on the entity class
 * explaining the opt-out rationale.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IgnoreSoftUkAudit {}
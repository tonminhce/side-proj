# 30 — Known Issues

> Pre-existing bugs, workarounds applied, and the long-term fix. **Read this before debugging.**

## Issue summary

| # | Issue | Status | Story | Severity |
|---|-------|--------|-------|----------|
| 1 | Util double-registration: `FileProperties`/`FolderProperties`/`TelegramProperties` had both `@Configuration` and `@ConfigurationProperties` | Fixed in commit `c58ddd2` | F1–F17 review | high |
| 2 | Checkout saga 2.5 entity scanning fails (`Not a managed type: InventoryReservation`) | **Open — see below** | 2.5 | high |
| 3 | DevSecurityConfig bean-order race (multiple SecurityFilterChain compete) | **Open** | this session | medium |
| 4 | `CatalogEventListener` references `JavaDirectInventoryReservationAdapter` — checkout-only issue | **See #2** | 2.5 | high |
| 5 | Modulith `EventPublicationRegistry` bean not auto-wired | Documented; `EventsAutoConfiguration` exists but bean-order in catalog may be wrong | 2.5 follow-up | medium |
| 6 | Runtime smoke had bean-name conflict (`globalExceptionHandler` in both util + catalog) | Fixed in commit `d7b8066` (`UtilGlobalExceptionHandler` rename) | F1–F17 | high |
| 7 | `UtilsAutoConfiguration` had `@ComponentScan` leaking util `@Component`s into service contexts | Fixed in commit `6dd265a` (removed `@ComponentScan`) | this session | high |
| 8 | All services use `oauth2-resource-server` → API returns 401 to direct curls (BFFs are intended entry) | By design | 5.4 (auth story) | by-design |

---

## Issue 1: Util double-registration (FIXED ✅)

**Symptom:** Spring Boot context fails to start with `BeanDefinitionStoreException` about bean `globalExceptionHandler` being defined twice.

**Root cause:** `util/src/main/java/vn/vnpt/util/exception/GlobalExceptionHandler.java` and `services/catalog/.../infrastructure/web/GlobalExceptionHandler.java` both had simple class name `GlobalExceptionHandler`. Spring's component scan generates bean name from class simple name, leading to collision.

**Fix (commit `d7b8066`):** Renamed util's class to `UtilGlobalExceptionHandler` (bean name `utilGlobalExceptionHandler`).

**Test:**
```bash
grep -r "@RestControllerAdvice" services/util 2>/dev/null
# Should show: util/exception/UtilGlobalExceptionHandler.java
```

---

## Issue 2: Checkout saga 2.5 entity scanning (OPEN ❌)

**Symptom:** Checkout fails to start with:
```
Caused by: Not a managed type: class vn.vnpt.inventory.domain.InventoryReservation
```

**Root cause:** Story 2.5 added `JavaDirectInventoryReservationAdapter` (in checkout) that depends on inventory's `ReserveInventoryUseCase` (a `@Service`). Spring Boot 4 removed `@EntityScan`, and `@EnableJpaRepositories` alone is not enough — Hibernate can't find inventory's `@Entity` types in checkout's classpath.

**Workarounds attempted (all failed for various reasons):**
1. `spring.jpa.packages-to-scan: vn.vnpt.checkout, vn.vnpt.inventory` — Spring Boot 4 doesn't recognize this property anymore.
2. `spring.jpa.properties.hibernate.archive.scanner.detect: class,package` — was added but `InventoryReservation` still not found.
3. Custom `LocalContainerEntityManagerFactoryBean` bean — produced compile errors.
4. Expanding `@ComponentScan(basePackages = ...)` — entities are JPA-scanned, not component-scanned.

**Current state:** Checkout won't start. Other 4 services (catalog, inventory, cart, payment) are fine.

**Long-term fix:** inventory should expose a Spring Boot autoconfig that provides the `EntityManagerFactory` and `JpaRepositoriesRegistrar` for its entities, so any service depending on inventory gets them. This is a multi-day refactor (probably a Story 4.x or 10.x).

**For now, skip checkout** — verify the other 4 services per [00-quickstart.md](00-quickstart.md).

---

## Issue 3: DevSecurityConfig bean-order race (OPEN ❌)

**Symptom:** `DevSecurityConfig` (a `@Profile("dev") @Configuration` that permits `/api/**`) sometimes doesn't win over the default oauth2-resource-server SecurityFilterChain. Result: `/api/*` endpoints still return 401.

**Root cause:** Multiple `SecurityFilterChain` beans compete; ordering depends on `@Order` annotation presence, and the default one doesn't have an explicit order.

**Workaround:** No fix attempted — Spring Security with multiple filter chains is fragile, and the proper fix is to make BFFs the entry point (Story 5.4).

**For now:** Skip API-level curl tests. Use the `/actuator/health` endpoint (Spring Boot whitelists it) and direct DB inspection.

---

## Issue 4: `JavaDirectInventoryReservationAdapter` references (covered by #2)

This is a sub-symptom of Issue #2. Story 2.5 added this adapter to checkout; it requires `ReserveInventoryUseCase` bean from inventory; the bean isn't created because Issue #2 blocks entity scanning.

**Same fix as #2.**

---

## Issue 5: Modulith `EventPublicationRegistry` bean (PARTIAL ⚠️)

**Symptom:** Earlier sessions saw `No qualifying bean of type 'org.springframework.modulith.events.core.EventPublicationRegistry' available` when starting catalog (or any service that uses Spring Modulith autoconfig with `UtilsAutoConfiguration` excluded).

**Status:** Fixed in commit `6dd265a`:
- Removed `@ComponentScan` from `UtilsAutoConfiguration` so util's `@Component` beans (like `FileUtil` with placeholder requirements) don't leak into service contexts.
- Added `util/events/EventsAutoConfiguration` providing `EventPublicationRegistry`.
- Registered `EventsAutoConfiguration` in `META-INF/spring/...AutoConfiguration.imports`.

**Caveat:** Whether `EventsAutoConfiguration` is actually loaded in each service depends on bean-order. Empirically catalog now boots. If you see "EventPublicationRegistry" missing, restart and check logs.

---

## Issue 6: GlobalExceptionHandler bean name conflict (FIXED ✅)

See Issue #1.

---

## Issue 7: `UtilsAutoConfiguration` leaking `@Component`s (FIXED ✅)

**Symptom:** Service boots fail with `Caused by: Could not resolve placeholder 'folder.temp.timeToLive'` even though `UtilsAutoConfiguration` is excluded.

**Root cause:** `@ComponentScan` (without `basePackages`) on `UtilsAutoConfiguration` was scanning the entire `vn.vnpt.util` package, picking up `@Component` classes like `FileUtil`. These classes' `@Value("${folder.temp.timeToLive}")` then failed because util's `application.yml` isn't on the service's classpath.

**Fix (commit `6dd265a`):** Removed `@ComponentScan` from `UtilsAutoConfiguration`. Now util's `@Component` beans are not auto-scanned into service contexts.

---

## Issue 8: All services use `oauth2-resource-server` (BY DESIGN)

**Symptom:** All `/api/*` endpoints return `401 Unauthorized` to direct curl requests, even with `X-Tenant: default` header.

**Root cause:** Each service's pom has `spring-boot-starter-oauth2-resource-server` as a transitive dep (via `util`). This auto-configures Spring Security to require a JWT.

**By design:** Services are meant to be called by BFFs (admin-bff, storefront-bff) which handle auth and forward the request with a JWT. Direct curl to services is not the intended access path.

**Workaround for dev smoke:** Add a `@Profile("dev") @Configuration` with `SecurityFilterChain` that `permitAll()` for `/api/**`. Implemented in `DevSecurityConfig.java` in each of the 5 services. **Works for 3/5 services** (catalog, cart, payment). Inventory and checkout still get 401 because their custom adapter dependencies trigger Spring to use a different SecurityFilterChain.

**For a real test:** Use the BFFs as the entry point, or generate a dev JWT. Out of scope for this run.

---

## What to do when you hit an unknown issue

1. Check the relevant log: `tail -200 /tmp/smoke/<service>.log`
2. Find `Caused by:` line
3. Cross-reference this document
4. If new, document it in this file
5. Open a Story for the long-term fix

## Next

- [60-troubleshoot.md](60-troubleshoot.md) — symptom-based quick fix
- [50-services-overview.md](50-services-overview.md) — what each service does
- [40-verify-curl.md](40-verify-curl.md) — per-service curl cookbook

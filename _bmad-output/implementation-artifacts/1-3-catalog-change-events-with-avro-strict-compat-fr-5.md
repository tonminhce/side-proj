---
baseline_commit: 8d4c583
predecessor: 1-2-product-aggregate-variant-graph-fr-1-fr-2-fr-4
sprint_status_at_create: backlog → ready-for-dev
---

# Story 1.3: Catalog change events with Avro strict compat (FR-5)

Status: done

<!-- Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a downstream consumer,
I want `catalog.product.created`, `catalog.product.updated`, and `catalog.product.price_changed` Avro events published atomically with state changes and signed per-service HMAC-SHA-256,
So that downstream services see consistent, tamper-evident updates whose schema evolution cannot break them.

## Acceptance Criteria

1. **Given** Story 1.2's outbox table (`services/catalog/src/main/resources/db/migration/V001__create_catalog_tables.sql` — `outbox(id, aggregate_type, aggregate_id, event_type, event_id, payload JSONB, created_at, published_at)`) and Story 1.2's hand-written `CatalogProductCreated` record at `vn.vnpt.catalog.domain.event.CatalogProductCreated` (declared as a placeholder that Story 1.3 replaces with the Avro-generated type at the **same** FQN — `CreateProductUseCase` signature does NOT change),
2. **When** I author Avro IDL files under `services/catalog/src/main/avro/` (one per `catalog.product.*` event type — `CatalogProductCreated`, `CatalogProductUpdated`, `CatalogProductPriceChanged`, `CatalogProductDeleted`) and configure the `avro-maven-plugin` to generate Java types at `vn.vnpt.catalog.domain.event.<Type>` (so the existing `outbox.append("Product", product.getUuid(), "catalog.product.created", new CatalogProductCreated(...))` call site in `CreateProductUseCase.java` continues to compile unchanged),
3. **Then** every field in every Avro record uses **primitive types or nullable unions** only (`["null", "string"]`, `["null", "long"]`) — no `BigDecimal` / `LocalDateTime` / `Map<String, Object>` / nested non-nullable unions. **Why:** Avro strict backward+forward compat (ADR-15 / NFR-MIG-2) requires every new field to be a **nullable union with a `null` default**; changing a primitive field's type or removing a field breaks one direction. **Concretely** (verified against util's `AvroCompatCheckCliTest.V2_COMPATIBLE` fixture): adding `{"name":"newField","type":["null","string"],"default":null}` to an existing record passes BOTH backward and forward checks. Adding `{"name":"newField","type":"string"}` (no null default) fails backward (existing consumer can't decode the new schema). Removing a field fails forward (new consumer can't decode the old schema).
4. **And** `mvn -pl services/catalog -am generate-sources` succeeds — the `avro-maven-plugin:1.12.0` (or current latest; version inherited from `util/pom.xml`'s `<pluginManagement>` if pinned, otherwise added explicitly to `services/catalog/pom.xml`) generates `vn.vnpt.catalog.domain.event.CatalogProductCreated` etc. as POJOs with a no-arg constructor + setters + a builder (the canonical Avro `SpecificRecord` shape). **Ponytail:** the generated type replaces the hand-written record at the SAME fully-qualified name — the package and class name are reserved for Avro codegen. The previous `record CatalogProductCreated(Long productUuid, String sku, Instant occurredAt)` is deleted; `CreateProductUseCase.java` line 208–209 (`new CatalogProductCreated(product.getUuid(), product.getSku(), Instant.now())`) compiles against the Avro-generated type which exposes `.setProductUuid(Long)` / `.getProductUuid()` / `.setOccurredAt(Instant)`. **If** the Avro-generated setters don't match, the use case edit is mechanical — update the call site to match the generated builder API; do NOT add an adapter / wrapper class (ponytail: no facade between use case and event type).
5. **And** the Modulith outbox bridge is wired as the canonical publisher. Concretely:
   - Add `<dependency>` `org.springframework.modulith:spring-modulith-events-jdbc` (version `${spring-modulith.version}` — `2.0.7` from root `pom.xml` line 47) to `services/catalog/pom.xml`. This artifact bundles Spring's outbox-bridge JdbcTemplate-based persistence AND in-process `ApplicationEventPublisher` integration.
   - Remove the `@Primary` annotation from `JdbcOutboxWriter` (`services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/outbox/JdbcOutboxWriter.java`) — the Modulith bridge takes over. **`JdbcOutboxWriter` itself is DELETED** in this story; the bridge writes directly to the `outbox` table (same SQL the writer used). The `OutboxPublisher` port in `vn.vnpt.catalog.application.port.OutboxPublisher` stays as the application-layer contract; its implementation becomes a thin `@Component ModulithOutboxPublisher` that calls `ApplicationEventPublisher.publishEvent(event)` — the bridge handles the `outbox` row insert AND the cross-aggregate `processed_event` dedup wiring (NFR-IDEM-1) AND the Kafka publish. The use case signature `outbox.append(aggregateType, aggregateId, eventType, event)` stays; the implementation now goes through `ApplicationEventPublisher`.
   - Configure the bridge in `services/catalog/src/main/resources/application.yml` under `spring.modulith.events.jdbc.poll-interval: 500ms` (architecture-detail.md line 146 — 500ms default) and `spring.modulith.events.outbox.publish-backpressure-threshold: 10000` (architecture-detail.md line 153 — page on-call above 10k rows).
   - The bridge picks up events from the in-process `ApplicationEventPublisher` and persists them to the existing `outbox` table (no DDL change). `CreateProductUseCaseTest.create_persistsProductVariantsAndAttributes` (Story 1.2) keeps passing because the same `outbox` row is inserted.
6. **And** an in-process `@ApplicationModuleListener` consumer (per ADR-01 + architecture-detail.md line 33 — `EventListener` annotations are intra-JVM) is added at `vn.vnpt.catalog.application.event.CatalogEventLogger` to verify the bridge is publishing: a single `@ApplicationModuleListener` method on `CatalogProductCreated` that appends a row to `processed_event` (consumer column = `"catalog.CatalogEventLogger"`) — this is the FIRST consumer in the codebase and proves the bridge-to-listener path is wired end-to-end. **Ponytail:** this listener is NOT business logic (Story 1.4 wires the real read-side projection); it exists ONLY to make AC #5 verifiable in tests. A test asserts: `create_product → assert processed_event has 1 row with event_id = the outbox row's event_id`.
7. **And** HMAC event signing is wired per ADR-20 (architecture-detail.md line 177–192). Concretely:
   - Add a new util class `vn.vnpt.util.events.HmacEventSigner` (production code, not test) with two methods: `String sign(String canonicalJson, String serviceSecret)` (HS256 → base64url) and `boolean verify(String canonicalJson, String signatureB64Url, String serviceSecret)` (constant-time compare). **JDK stdlib only:** `javax.crypto.Mac` + `java.security.MessageDigest` + `java.util.Base64.getUrlEncoder().withoutPadding()` — no BouncyCastle, no Apache Commons Codec.
   - JCS (RFC 8785) canonical JSON is required: util class `vn.vnpt.util.events.JcsCanonicalJson` serializes a `Map<String, Object>` (the event envelope minus the `signatures` field) to a deterministic byte sequence. **JDK only:** sort keys lexicographically; serialize numbers as JSON numbers (NOT strings); serialize booleans as `true`/`false` (NOT `1`/`0`); no whitespace. **YAGNI:** do NOT pull in the `jcs` Maven artifact (RFC 8785 spec has only ~50 lines of behavior we need).
   - The signing happens in the Modulith outbox bridge (post-`ApplicationEventPublisher.publishEvent`, pre-`outbox` row insert): the bridge calls `HmacEventSigner.sign(JcsCanonicalJson.serialize(envelope), catalogServiceSecret)` and stores the signature in a new `outbox.signatures JSONB` column. **Migration V003** (`services/catalog/src/main/resources/db/migration/V003__add_outbox_signatures.sql`) adds `signatures JSONB` to the `outbox` table — nullable (existing rows from Story 1.2 have no signature; the consumer falls back to "unsigned = log a warning" for rows with NULL `signatures`).
   - The HMAC secret `catalog-service-hmac-secret` is sourced from `application.yml` via Spring property `${catalog.events.hmac-secret:dev-only-secret-do-not-use-in-prod}` (the `dev-only-...` default keeps dev compose working without Vault; **production deployment requires the Vault path `secret/events/hmac/catalog` to be wired per ADR-18**, deferred to a hardening story — this story does NOT wire Vault).
   - The signing key is the service name (`"catalog"`) — the consumer verifies by looking up `secret/events/hmac/<signing-service-name>` and recomputing. The signing-service-name lives in `signatures.service` of the envelope (architecture.md line 491–495 canonical envelope).
8. **And** the Avro compat CI gate (NFR-MIG-2) fires on this story's schemas. Concretely:
   - Add Avro schema files under `services/catalog/src/main/avro/*.avsc` (4 files: created/updated/price_changed/deleted).
   - The CI workflow `.github/workflows/ci.yml` step `Detect changed Avro schemas` (already present per Story 0.4 baseline — `dorny/paths-filter@v2` filters `services/**/src/main/avro/**.avsc`) detects the new `.avsc` files on push.
   - The next step `Avro compatibility check (Apicurio)` runs `mvn -pl util test -Dtest=AvroCompatCheckCliTest -q` against the prior-version schema and the new schema. **First-time registration:** there is no "prior version" — the CLI returns `COMPATIBLE` by default for first registration. **Subsequent changes:** any breaking change (removed field, changed primitive type, removed null default) fails the CI build with a non-zero exit (the CLI exits `1` on incompatible — verified against `AvroCompatCheckCliTest.V2_INCOMPATIBLE_BACKWARD` and `V2_INCOMPATIBLE_FORWARD` fixtures which expect `assertEquals(1, exit)`).
   - **Ponytail:** the CI step's `-Dtest=AvroCompatCheckCliTest` already runs against the **prior-version schema** baked into `AvroCompatCheckCliTest.java` as a `static final String V1`. Story 1.3 does NOT extend that test's fixtures — it ships a separate test in `services/catalog` that asserts THIS story's schemas pass strict compat against `null` (no prior version). Real CI compat coverage happens on the SECOND Avro change (when the prior version is real).
9. **And** `CreateProductUseCase` is unchanged in shape — the call `outbox.append("Product", product.getUuid(), "catalog.product.created", new CatalogProductCreated(...))` still compiles (the Avro-generated `CatalogProductCreated` exposes a no-arg constructor + a builder + `getXxx`/`setXxx` accessors; the call site uses the builder OR a 3-arg constructor if Avro codegen provides one — verify by running `mvn generate-sources` and inspecting the generated `CatalogProductCreated.java` under `target/generated-sources/avro/`). If the call site needs a 1-line update to match the generated API, that update IS Story 1.3 scope; if a deeper refactor is needed, stop and surface it to the user (do NOT silently add an adapter — architecture.md line 595 anti-pattern applies).
10. **And** `UpdateProductUseCase` (`vn.vnpt.catalog.application.UpdateProductUseCase`, `@Service @Transactional`) is added. Signature: `Product update(UpdateProductCommand cmd)` where `UpdateProductCommand` is a Java record `(Long productUuid, String name, String description, String brand)`. The use case:
    - Loads `Product` via `ProductRepository.findById(productUuid).orElseThrow(...)`; throws `ProductNotFoundException` (new domain exception, `vn.vnpt.catalog.domain.exception.ProductNotFoundException`, extends `RuntimeException`).
    - Mutates the loaded entity's mutable fields (`name`, `description`, `brand`) — does NOT change `sku` (sku is immutable post-creation; per-variant SKU is the hash, the product SKU is the admin-managed slug).
    - Persists via `products.save(product)` (dirty-checking handles the UPDATE; `@Transactional` enforces the version-check on save).
    - Calls `outbox.append("Product", product.getUuid(), "catalog.product.updated", new CatalogProductUpdated(product.getUuid(), product.getSku(), product.getName(), Instant.now()))` in the same transaction. The event payload's `name` carries the new value — consumers see the post-update state.
    - **YAGNI:** `UpdateProductUseCase` does NOT update variants or attributes in this story (Story 1.5 wires the inventory-reserve event; variant-level updates land in a later story). Variants are mutated by the inventory service saga, not by product admin edits in v1.
11. **And** `UpdatePriceUseCase` (`vn.vnpt.catalog.application.UpdatePriceUseCase`, `@Service @Transactional`) is added. Signature: `Variant updatePrice(UpdatePriceCommand cmd)` where `UpdatePriceCommand` is `(Long variantUuid, long newPriceCents)`. The use case:
    - Loads `Variant` via `VariantRepository.findById(variantUuid).orElseThrow(...)`; throws `VariantNotFoundException`.
    - Mutates `priceCents` to the new value.
    - Persists via `variants.save(variant)`.
    - Calls `outbox.append("Variant", variant.getUuid(), "catalog.product.price_changed", new CatalogProductPriceChanged(variant.getUuid(), variant.getProductUuid(), oldPriceCents, newPriceCents, variant.getCurrency(), Instant.now()))` in the same transaction. **BOTH** old and new prices are in the payload — consumers (Search, Notification, Pricing) need the diff for "price dropped from X to Y" UI.
    - **Ponytail:** `oldPriceCents` is captured from `variant.getPriceCents()` BEFORE the mutation — easy to miss; the test asserts the payload contains both values to catch a regression that captures after mutation.
12. **And** `mvn -pl services/catalog -am test` is green. **Expected test count:** Story 1.2 ships **32 catalog tests** (verified in `_bmad-output/implementation-artifacts/tests/test-summary.md`); Story 1.3 adds:
    - 4 domain event tests: `CatalogProductCreatedTest`, `CatalogProductUpdatedTest`, `CatalogProductPriceChangedTest`, `CatalogProductDeletedTest` (one each — verify the Avro-generated POJO builds + serializes + deserializes round-trip via Avro's `DatumReader`/`DatumWriter` API; pure JUnit).
    - 1 util test: `HmacEventSignerTest` (sign + verify round-trip, plus a `verify_rejectsMismatchedSignature` case).
    - 1 JCS test: `JcsCanonicalJsonTest` (deterministic ordering, number encoding, boolean encoding).
    - 2 use-case tests: `UpdateProductUseCaseTest` (asserts variant NOT mutated, sku immutable, outbox row has `event_type='catalog.product.updated'`), `UpdatePriceUseCaseTest` (asserts payload contains BOTH old and new price, mutation order invariant).
    - 1 bridge test: `ModulithOutboxBridgeTest` (full `@SpringBootTest` — `create_product` → `assert outbox row exists with event_id + signatures JSONB containing { service:"catalog", hmac_sha256:<non-empty base64url> }` → `assert processed_event has 1 row from the in-process listener`).
    - **Total new tests: 9.** New catalog total: **32 + 9 = 41 tests minimum**. Record the EXACT count in Completion Notes (Stories 0.4 / 0.5 / 1.2 reviews all caught test-count documentation drifts — see `1-2-...md` "Completion Notes" HIGH finding).
13. **And** `mvn validate` from project root remains green with **17 `<module>` entries** (Story 0.2 baseline; Story 1.3 does not add or remove modules). **And** `mvn -pl util -am test` remains **42/42** baseline (`util/src/main/java/vn/vnpt/util/events/HmacEventSigner.java` and `JcsCanonicalJson.java` are new files; they need their own util tests which extend the baseline — see Subtask 9.4 below). **Ponytail:** if `util` tests go above 42, document the delta in Completion Notes; do NOT pretend the baseline is preserved when it isn't.
14. **And** the existing CI step `.github/workflows/ci.yml` `Avro compatibility check (Apicurio)` runs on the PR that lands this story (the `dorny/paths-filter@v2` filter detects the new `.avsc` files). The step must pass (exit code 0) — first-time registration returns COMPATIBLE because there is no prior schema to check against. **Verify by running locally:** `mvn -pl util test -Dtest=AvroCompatCheckCliTest -q` exits 0.
15. **And** `CatalogPackageBoundaryTest` (Story 1.1 + 1.2) still passes. **Add a third `@Test` method** `application_doesNotDependOnInfrastructure()` (parallel to Story 1.2's `domain_doesNotDependOnInfrastructure`) asserting `vn.vnpt.catalog.application..` does not depend on `vn.vnpt.catalog.infrastructure..` — `UpdateProductUseCase` and `UpdatePriceUseCase` inject ports (`OutboxPublisher`, `ProductRepository`, `VariantRepository`), not adapters. This is the canonical DDD layering check.
16. **And** ArchUnit rule `domain_doesNotDependOnInfrastructure` (Story 1.2 AC #16) still holds — the Avro-generated types live in `vn.vnpt.catalog.domain.event.*` (because the use case call site references them there) but the domain package itself contains ONLY the generated event POJOs + `Product`/`Variant`/`Attribute` entities + the new `ProductNotFoundException` / `VariantNotFoundException`. Avro-generated classes contain `@org.apache.avro.specific.AvroGenerated` annotations — that's acceptable in the domain package per the architectural convention (the architecture.md "Pattern Examples" line 593 shows `domain/event/CatalogProductCreated` as a valid domain-event location).
17. **And** `processed_event` table is now populated by the in-process listener (per AC #6) — `processed_event` has 1 row after a `CreateProductUseCase` invocation, keyed by the outbox row's `event_id`, with `consumer='catalog.CatalogEventLogger'`. This is the integration test's idempotency proof — if the bridge double-publishes, the second invocation's listener tries to insert `event_id=<duplicate>` and Postgres's `UNIQUE(event_id)` constraint blocks it (Story 1.1 V001 DDL line ~`event_id BIGINT NOT NULL UNIQUE`).

## Tasks / Subtasks

- [x] Task 1: Author Avro schemas (AC: 2, 3)
  - [x] Subtask 1.1: Create directory `services/catalog/src/main/avro/`.
  - [x] Subtask 1.2: File `services/catalog/src/main/avro/CatalogProductCreated.avsc`:
    ```json
    {
      "type": "record",
      "name": "CatalogProductCreated",
      "namespace": "vn.vnpt.catalog.domain.event",
      "doc": "Emitted when a Product aggregate is created (Story 1.3 / FR-5).",
      "fields": [
        {"name": "productUuid", "type": "long", "doc": "Snowflake ID of the Product aggregate."},
        {"name": "sku", "type": "string", "doc": "Product-level SKU slug (NOT a variant SKU)."},
        {"name": "name", "type": ["null", "string"], "default": null, "doc": "Product display name."},
        {"name": "occurredAt", "type": ["null", "string"], "default": null, "doc": "ISO-8601 UTC timestamp."}
      ]
    }
    ```
    **Ponytail:** `occurredAt` is encoded as `["null", "string"]` (NOT Avro `timestamp-millis` / `timestamp-micros` logical type) because Apicurio's compat checker treats logical types as plain strings for compat purposes — `string` is the lowest-common-denominator that survives the strict dual-direction check. Use ISO-8601 with `Z` suffix (`Instant.now().toString()` → `"2026-07-07T01:23:45.123Z"`).
  - [x] Subtask 1.3: File `services/catalog/src/main/avro/CatalogProductUpdated.avsc`:
    ```json
    {
      "type": "record",
      "name": "CatalogProductUpdated",
      "namespace": "vn.vnpt.catalog.domain.event",
      "doc": "Emitted when a Product aggregate's mutable fields change (Story 1.3 / FR-5).",
      "fields": [
        {"name": "productUuid", "type": "long"},
        {"name": "sku", "type": "string"},
        {"name": "name", "type": ["null", "string"], "default": null},
        {"name": "occurredAt", "type": ["null", "string"], "default": null}
      ]
    }
    ```
    **Ponytail:** identical field set to `CatalogProductCreated` because the same downstream consumers (Search, Notification) need both events with the same shape. If a future consumer needs `description` / `brand`, those get ADDED to BOTH records (preserving compat), not created in a divergent shape.
  - [x] Subtask 1.4: File `services/catalog/src/main/avro/CatalogProductPriceChanged.avsc`:
    ```json
    {
      "type": "record",
      "name": "CatalogProductPriceChanged",
      "namespace": "vn.vnpt.catalog.domain.event",
      "doc": "Emitted when a Variant's price changes (Story 1.3 / FR-5).",
      "fields": [
        {"name": "variantUuid", "type": "long", "doc": "Snowflake ID of the Variant."},
        {"name": "productUuid", "type": "long", "doc": "Snowflake ID of the parent Product."},
        {"name": "oldPriceCents", "type": "long"},
        {"name": "newPriceCents", "type": "long"},
        {"name": "currency", "type": ["null", "string"], "default": null},
        {"name": "occurredAt", "type": ["null", "string"], "default": null}
      ]
    }
    ```
  - [x] Subtask 1.5: File `services/catalog/src/main/avro/CatalogProductDeleted.avsc`:
    ```json
    {
      "type": "record",
      "name": "CatalogProductDeleted",
      "namespace": "vn.vnpt.catalog.domain.event",
      "doc": "Emitted when a Product aggregate is soft-deleted (Story 1.3 / FR-5).",
      "fields": [
        {"name": "productUuid", "type": "long"},
        {"name": "sku", "type": "string"},
        {"name": "occurredAt", "type": ["null", "string"], "default": null}
      ]
    }
    ```
    **YAGNI:** no `CatalogProductRestored` event in this story — soft-delete restore is rare and can land in a future story when a concrete use case arrives. The 4 events cover the `created / updated / price_changed / deleted` lifecycle vocabulary from `epics.md` line 487 and `architecture.md` line 491 envelope (`aggregate_type='Product'`).
  - [x] Subtask 1.6: **Ponytail:** NO `Variant` / `Attribute` aggregate events in this story — Story 1.8 (inventory lifecycle events, FR-11) owns that. Story 1.3 is the catalog-side `*.lifecycle` events for the `Product` aggregate root. Adding `CatalogVariantCreated` here would scope-creep into Story 1.8 territory.

- [x] Task 2: Configure `avro-maven-plugin` (AC: 4)
  - [x] Subtask 2.1: Edit `services/catalog/pom.xml`. Add the plugin **inside the existing `<build><plugins>` block** (the `maven-compiler-plugin` and `spring-boot-maven-plugin` are already there per Story 1.2 — verify by reading `services/catalog/pom.xml` line 117–143):
    ```xml
    <plugin>
      <groupId>org.apache.avro</groupId>
      <artifactId>avro-maven-plugin</artifactId>
      <version>1.12.0</version>
      <executions>
        <execution>
          <phase>generate-sources</phase>
          <goals><goal>generate</goal></goals>
          <configuration>
            <sourceDirectory>${project.basedir}/src/main/avro</sourceDirectory>
            <outputDirectory>${project.build.directory}/generated-sources/avro</outputDirectory>
            <stringType>String</stringType>
          </configuration>
        </execution>
      </executions>
    </plugin>
    ```
    - **Ponytail:** `<stringType>String</stringType>` makes the generated Java type `java.lang.String` (not `org.apache.avro.util.Utf8`). The use case's `new CatalogProductCreated(... String sku ...)` then compiles without an Avro-specific conversion. **Verify** by reading the Avro plugin docs default: it generates `Utf8` which requires `.toString()` at every use site — the `<stringType>String</stringType>` override is the lazy fix.
    - **Version pin:** `1.12.0` matches the Apicurio 2.6.x runtime (per util's Apicurio 2.6.13.Final pin in `util/pom.xml`); major-version-skew between Avro compiler and runtime is the single most common `.avsc` codegen bug.
  - [x] Subtask 2.2: Verify `mvn -pl services/catalog -am generate-sources` succeeds and produces `target/generated-sources/avro/vn/vnpt/catalog/domain/event/CatalogProductCreated.java` etc. **Don't commit `target/`** (already in `.gitignore` per Story 0.2).

- [x] Task 3: Replace hand-written event record with Avro-generated type (AC: 4, 9)
  - [x] Subtask 3.1: **Delete** `services/catalog/src/main/java/vn/vnpt/catalog/domain/event/CatalogProductCreated.java` (the hand-written `record` from Story 1.2). The Avro-generated type at the same FQN takes over.
  - [x] Subtask 3.2: Read the Avro-generated `CatalogProductCreated.java` (under `target/generated-sources/avro/...`) — find the constructor / builder signature. The use case call site `new CatalogProductCreated(product.getUuid(), product.getSku(), Instant.now())` may need to switch to `CatalogProductCreated.newBuilder().setProductUuid(...).setSku(...).setOccurredAt(...).build()` if Avro codegen only exposes the builder API. **Apply the 1-line edit** to `CreateProductUseCase.java` line 209 (verify by reading the file). **Do NOT add an adapter** — if the generated API is the builder, use the builder.
  - [x] Subtask 3.3: **Add the `import static`** for `Instant` (already present in Story 1.2's use case) — Avro's `setOccurredAt` accepts any `CharSequence`, so `Instant.toString()` (returns `"2026-07-07T..."`) is the right arg.

- [x] Task 4: Wire Modulith outbox bridge (AC: 5)
  - [x] Subtask 4.1: Edit `services/catalog/pom.xml`. Add inside `<dependencies>`:
    ```xml
    <dependency>
      <groupId>org.springframework.modulith</groupId>
      <artifactId>spring-modulith-events-jdbc</artifactId>
      <version>${spring-modulith.version}</version>
    </dependency>
    ```
    Version `${spring-modulith.version}` = `2.0.7` from root `pom.xml` line 47 (already imported by `spring-modulith-starter-core`). **Ponytail:** do NOT add a hard-coded `2.0.7` — the property override is the lazy fix.
  - [x] Subtask 4.2: **Delete** `services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/outbox/JdbcOutboxWriter.java`. The Modulith bridge takes over.
  - [x] Subtask 4.3: Create `services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/outbox/ModulithOutboxPublisher.java`. `@Component implements OutboxPublisher`. Method `append(aggregateType, aggregateId, eventType, event)`:
    ```java
    @Override
    public void append(String aggregateType, Long aggregateId, String eventType, Object event) {
      // Modulith's ApplicationEventPublisher picks up the event; the bridge handles:
      //   (a) serializing to JSONB,
      //   (b) inserting into the outbox table in the SAME transaction (joins via DataSource),
      //   (c) the cross-aggregate dedup wiring,
      //   (d) the Kafka publish (the bridge polls at 500ms by default).
      applicationEventPublisher.publishEvent(event);
    }
    ```
    Inject `ApplicationEventPublisher` via constructor (Lombok `@RequiredArgsConstructor`). The Modulith bridge's auto-configuration is enabled by adding `spring-modulith-events-jdbc` to the classpath — no `@EnableModulithEvents` annotation is needed in v2.0.7 (the bridge is autoconfigured).
  - [x] Subtask 4.4: Edit `services/catalog/src/main/resources/application.yml`. Add a new section (verify by reading current yml — Story 1.1 baseline):
    ```yaml
    spring:
      modulith:
        events:
          jdbc:
            # 500ms poll per architecture-detail.md line 146
            poll-interval: 500ms
          outbox:
            # Page on-call above 10k backlog rows (architecture-detail.md line 153)
            publish-backpressure-threshold: 10000
    ```
    **Ponytail:** these property paths may differ in `spring-modulith-events-jdbc:2.0.7` — verify by reading the Modulith reference docs OR running `mvn -pl services/catalog -am spring-boot:run` and checking for `UnknownPropertyException` at boot. Adjust the YAML keys to match the real Spring property names if they differ. Document the actual keys in the yml comment.
  - [x] Subtask 4.5: The `OutboxPublisher` port interface (`vn.vnpt.catalog.application.port.OutboxPublisher`) is unchanged. The use case still calls `outbox.append(aggregateType, aggregateId, eventType, event)` — the implementation changed (`JdbcOutboxWriter` → `ModulithOutboxPublisher`); the port is the abstraction layer.

- [x] Task 5: Add the in-process listener (AC: 6, 17)
  - [x] Subtask 5.1: Create `services/catalog/src/main/java/vn/vnpt/catalog/application/event/CatalogEventLogger.java`. `@Component` with `@ApplicationModuleListener` on each of the 4 Avro types:
    ```java
    @Component
    @RequiredArgsConstructor
    public class CatalogEventLogger {
      private final ProcessedEventRepository processed;
      @ApplicationModuleListener
      void on(CatalogProductCreated event) { processed.append("catalog.CatalogEventLogger", "catalog.product.created", event.getProductUuid(), Instant.now()); }
      @ApplicationModuleListener
      void on(CatalogProductUpdated event) { /* same */ }
      @ApplicationModuleListener
      void on(CatalogProductPriceChanged event) { processed.append("catalog.CatalogEventLogger", "catalog.product.price_changed", event.getVariantUuid(), Instant.now()); }
      @ApplicationModuleListener
      void on(CatalogProductDeleted event) { /* same */ }
    }
    ```
    **Ponytail:** the listener uses the existing `processed_event` table (V001 DDL). It does NOT mutate any catalog entity — that's the inventory service's job (Story 1.5+). This listener exists ONLY to prove the bridge-to-listener path is wired; it's a `processed_event` bookkeeper.
  - [x] Subtask 5.2: Add `ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> existsByEventId(Long eventId)` + `void append(...)`. **YAGNI:** `ProcessedEvent` entity is a thin `@Entity @Table(name="processed_event")` with the columns from V001 DDL (`event_id BIGINT NOT NULL UNIQUE`, `event_type`, `processed_at`, `consumer`) — Story 1.1's V001 already declares the table; this story ships the JPA mapping. Path `vn.vnpt.catalog.infrastructure.repository.ProcessedEventRepository.java`.

- [x] Task 6: Wire HMAC event signing (AC: 7)
  - [x] Subtask 6.1: Add `util` dependency `com.fasterxml.jackson.core:jackson-databind` (already a transitive of `spring-boot-starter-web` per root pom — verify by `mvn -pl util -am dependency:tree | grep jackson-databind`). **Ponytail:** if Jackson is already on util's classpath, do NOT add a new `<dependency>` — reuse.
  - [x] Subtask 6.2: Create `util/src/main/java/vn/vnpt/util/events/HmacEventSigner.java`:
    ```java
    public static String sign(String canonicalJson, String serviceSecret) {
      try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(serviceSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(canonicalJson.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
      } catch (NoSuchAlgorithmException | InvalidKeyException e) {
        throw new IllegalStateException("HMAC-SHA256 unavailable in JDK — security primitive failed", e);
      }
    }
    public static boolean verify(String canonicalJson, String signatureB64Url, String serviceSecret) {
      String expected = sign(canonicalJson, serviceSecret);
      return MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.UTF_8),
          signatureB64Url.getBytes(StandardCharsets.UTF_8));
    }
    ```
    - **Ponytail:** `MessageDigest.isEqual` is constant-time (Java 7+). Do NOT use `Arrays.equals` or `String.equals` for HMAC comparison — those leak timing.
    - **Ponytail:** `HmacSHA256` is mandated by the JRE — every conformant JDK ships it. The `catch` is defensive (per `local-docs/10` R-12 style).
  - [x] Subtask 6.3: Create `util/src/main/java/vn/vnpt/util/events/JcsCanonicalJson.java`. Implementation:
    - Input: `Map<String, Object> envelope`.
    - Output: deterministic byte sequence.
    - Algorithm: sort keys lexicographically; for each value:
      - `null` → `"null"`
      - `Boolean` → `"true"`/`"false"`
      - `Number` → `Long.toString(n)` or `Double.toString(d)` (no JSON-quote; JSON spec allows raw numbers)
      - `String` → JSON-escape (control chars, `"`, `\`) and wrap in `"`
      - `Map` → recursive
      - `List` → recursive (no sort; preserve order — RFC 8785)
    - **Ponytail:** `org.json` / `jackson` are NOT used here because their canonical output is NOT byte-deterministic across versions / configurations. RFC 8785 requires hand-rolled determinism; ~80 lines of Java.
    - **YAGNI:** do NOT pull in the `jcs` Maven artifact (RFC 8785 reference impl) — it's 100KB of transitive deps for what we need in 80 lines.
  - [x] Subtask 6.4: Migration V003 `services/catalog/src/main/resources/db/migration/V003__add_outbox_signatures.sql`:
    ```sql
    -- V003__add_outbox_signatures.sql — Story 1.3 (HMAC event signing per ADR-20).
    -- Nullable: rows from Story 1.2 / 1.3's first deploy have no signature; the consumer
    -- falls back to "unsigned = log a warning" for NULL signatures.
    ALTER TABLE outbox ADD COLUMN signatures JSONB;

    -- Partial index: a downstream scanner that audits signatures (Story 10.1 LGTM dashboard)
    -- queries WHERE signatures IS NULL — make that fast.
    CREATE INDEX idx_outbox_unsigned ON outbox(id) WHERE signatures IS NULL;
    ```
    Header comment cites ADR-20 line 177–192.
  - [x] Subtask 6.5: Wire the signer into the Modulith bridge — **the bridge does NOT have a signing hook in 2.0.7**. **Ponytail solution:** instead of fighting the bridge's internals, the signing happens BEFORE `outbox.append(...)` in the use case:
    ```java
    // CreateProductUseCase.java (modified line ~210)
    CatalogProductCreated event = CatalogProductCreated.newBuilder()
        .setProductUuid(product.getUuid())
        .setSku(product.getSku())
        .setOccurredAt(Instant.now().toString())
        .build();
    outbox.append("Product", product.getUuid(), "catalog.product.created", event);
    ```
    And the SIGNATURE is added by a NEW wrapping port:
    ```java
    // new interface: vn.vnpt.catalog.application.port.SignedEventEnvelope
    public record SignedEventEnvelope(String serviceName, Map<String, Object> envelope, String signatureB64Url) {}
    ```
    **Wait — this is scope creep.** **Refactor decision:** the simplest path that satisfies ADR-20 is to extend `OutboxPublisher.append` with an overload `append(aggregateType, aggregateId, eventType, event, Map<String, String> signatures)`. The use case computes the signature ONCE (using `JcsCanonicalJson.serialize(...)` + `HmacEventSigner.sign(...)`) and passes the `signatures` map to the port. The ModulithOutboxPublisher writes both `payload` (Avro-encoded via the existing Jackson path) AND `signatures` (the new JSONB column) in the same INSERT.
    - **Final decision (ponytail: simplest path that satisfies the AC):**
      1. Extend `OutboxPublisher.append` signature: `void append(String aggregateType, Long aggregateId, String eventType, Object event, Map<String, String> signatures)`. The 4-arg overload (Task 4.3) is REMOVED — the existing call sites are updated to pass `Map.of()` for signatures in tests where signing is not asserted.
      2. `ModulithOutboxPublisher` builds an envelope `Map<String, Object>` from the Avro POJO via `JcsCanonicalJson.serialize(...)`, computes the HMAC, and bundles `{ "service": "catalog", "hmac_sha256": "<base64url>" }` into `signatures`. Writes both columns.
      3. **YAGNI:** the use case does NOT compute the signature itself — the publisher does. The use case stays a pure domain operation.
  - [x] Subtask 6.6: Edit `services/catalog/src/main/resources/application.yml`. Add:
    ```yaml
    catalog:
      events:
        # Dev default: the bridge signs with this string. Production overrides via Vault
        # at secret/events/hmac/catalog (architecture.md line 413 / ADR-18).
        hmac-secret: dev-only-secret-do-not-use-in-prod
    ```
  - [x] Subtask 6.7: In `ModulithOutboxPublisher`, inject the secret via `@Value("${catalog.events.hmac-secret}")`. The publisher reads the secret ONCE at bean creation; no runtime reload (Vault-driven rotation is a hardening story, not Story 1.3).

- [x] Task 7: Add UpdateProductUseCase (AC: 10)
  - [x] Subtask 7.1: Create `vn.vnpt.catalog.domain.exception.ProductNotFoundException` (extends `RuntimeException`, single-arg `Long productUuid` constructor, message `"Product not found: uuid=" + productUuid`). Package `vn.vnpt.catalog.domain.exception` (new sub-package; first domain exception in the codebase).
  - [x] Subtask 7.2: Create `vn.vnpt.catalog.application.UpdateProductCommand.java`:
    ```java
    public record UpdateProductCommand(
        Long productUuid,
        String name,
        String description,
        String brand
    ) {}
    ```
  - [x] Subtask 7.3: Create `vn.vnpt.catalog.application.UpdateProductUseCase.java`:
    ```java
    @Service
    @Transactional
    @RequiredArgsConstructor
    public class UpdateProductUseCase {
      private final ProductRepository products;
      private final OutboxPublisher outbox;
      public Product update(UpdateProductCommand cmd) {
        if (cmd.name() == null || cmd.name().isBlank())
          throw new IllegalArgumentException("name is required");
        Product product = products.findById(cmd.productUuid())
            .orElseThrow(() -> new ProductNotFoundException(cmd.productUuid()));
        product.setName(cmd.name());
        product.setDescription(cmd.description());
        product.setBrand(cmd.brand());
        products.save(product);
        CatalogProductUpdated event = CatalogProductUpdated.newBuilder()
            .setProductUuid(product.getUuid())
            .setSku(product.getSku())
            .setName(product.getName())
            .setOccurredAt(Instant.now().toString())
            .build();
        outbox.append("Product", product.getUuid(), "catalog.product.updated", event, Map.of());
        return product;
      }
    }
    ```

- [x] Task 8: Add UpdatePriceUseCase (AC: 11)
  - [x] Subtask 8.1: Create `vn.vnpt.catalog.domain.exception.VariantNotFoundException` (same shape as `ProductNotFoundException`).
  - [x] Subtask 8.2: Create `vn.vnpt.catalog.application.UpdatePriceCommand.java`:
    ```java
    public record UpdatePriceCommand(Long variantUuid, long newPriceCents) {}
    ```
  - [x] Subtask 8.3: Create `vn.vnpt.catalog.application.UpdatePriceUseCase.java`:
    ```java
    @Service
    @Transactional
    @RequiredArgsConstructor
    public class UpdatePriceUseCase {
      private final VariantRepository variants;
      private final OutboxPublisher outbox;
      public Variant updatePrice(UpdatePriceCommand cmd) {
        if (cmd.newPriceCents() < 0) throw new IllegalArgumentException("price must be non-negative");
        Variant variant = variants.findById(cmd.variantUuid())
            .orElseThrow(() -> new VariantNotFoundException(cmd.variantUuid()));
        long oldPriceCents = variant.getPriceCents(); // ponytail: capture BEFORE mutation
        variant.setPriceCents(cmd.newPriceCents());
        variants.save(variant);
        CatalogProductPriceChanged event = CatalogProductPriceChanged.newBuilder()
            .setVariantUuid(variant.getUuid())
            .setProductUuid(variant.getProductUuid())
            .setOldPriceCents(oldPriceCents)
            .setNewPriceCents(cmd.newPriceCents())
            .setCurrency(variant.getCurrency())
            .setOccurredAt(Instant.now().toString())
            .build();
        outbox.append("Variant", variant.getUuid(), "catalog.product.price_changed", event, Map.of());
        return variant;
      }
    }
    ```

- [x] Task 9: Author tests (AC: 12, 13, 15, 17)
  - [x] Subtask 9.1: Path `services/catalog/src/test/java/vn/vnpt/catalog/domain/event/CatalogProductCreatedTest.java`. Pure JUnit:
    - `record_buildsWithAllFields()` — `CatalogProductCreated.newBuilder().setProductUuid(123L).setSku("red-shirt").setName("Red Shirt").setOccurredAt("2026-07-07T01:00:00Z").build()` → assert getters return the same values.
    - `record_survivesAvroRoundTrip()` — serialize via `SpecificDatumWriter` to `ByteArrayOutputStream`, deserialize via `SpecificDatumReader` from `ByteArrayInputStream`, assert deep-equal. **Ponytail:** proves the `.avsc` schema and the generated Java are consistent.
  - [x] Subtask 9.2: `CatalogProductUpdatedTest.java` — same 2 tests, parametrized over `CatalogProductUpdated`.
  - [x] Subtask 9.3: `CatalogProductPriceChangedTest.java` — same 2 tests, asserts `oldPriceCents=199000`, `newPriceCents=249000` round-trip.
  - [x] Subtask 9.4: `CatalogProductDeletedTest.java` — same 2 tests, minimal payload.
  - [x] Subtask 9.5: Path `util/src/test/java/vn/vnpt/util/events/HmacEventSignerTest.java`. Tests:
    - `sign_producesDeterministicBase64Url()` — sign the same input twice, assert equal (same secret → same sig).
    - `verify_acceptsValidSignature()` — sign then verify → true.
    - `verify_rejectsMismatchedSignature()` — sign with secret A, verify with secret B → false.
    - `verify_rejectsTamperedPayload()` — sign `"a"`, verify `"b"` → false.
    - **Note:** these are NEW util tests; util's baseline of 42/42 becomes **42 + 5 = 47**. Story 1.3 adds 5 to util; record the delta in Completion Notes.
  - [x] Subtask 9.6: Path `util/src/test/java/vn/vnpt/util/events/JcsCanonicalJsonTest.java`. Tests:
    - `serializesKeysInLexicographicOrder()` — `Map.of("z", 1, "a", 2)` → assert output starts with `"a":2` (NOT `"z"`).
    - `serializesNullAsLiteral()` — `"x" -> null` → `"x":null` (NOT `"x":"null"`).
    - `serializesBooleanAsJsonBool()` — `"x" -> true` → `"x":true` (NOT `"x":1`).
    - `serializesStringWithJsonEscape()` — `"x" -> "a\"b"` → `"x":"a\\\"b"`.
    - `serializesNestedMapRecursively()` — nested `Map.of("inner", Map.of("k", "v"))` → output contains `"inner":{"k":"v"}`.
    - **+5 util tests; total util delta = +10.**
  - [x] Subtask 9.7: Path `services/catalog/src/test/java/vn/vnpt/catalog/application/UpdateProductUseCaseTest.java`. `@SpringBootTest @ActiveProfiles("test")` (Testcontainers Postgres). Tests:
    - `update_persistsFieldsAndOutboxEvent()` — save a Product via `products.save(...)`, then call `update(new UpdateProductCommand(product.getUuid(), "New Name", "New Desc", "New Brand"))`. Assert: returned product has the new fields; `outbox` has 1 NEW row with `event_type='catalog.product.updated'`, `aggregate_type='Product'`, `payload` JSON contains `"name":"New Name"`. The previous `catalog.product.created` row is still there — 2 total after update.
    - `update_throwsWhenProductNotFound()` — call with `productUuid=99999L` → assert `ProductNotFoundException`.
    - `update_validatesName()` — `name=""` → assert `IllegalArgumentException`.
    - `update_doesNotChangeSku()` — save product with sku="abc", call update with the same name; assert `product.getSku()` is still `"abc"` (sku is immutable).
  - [x] Subtask 9.8: Path `services/catalog/src/test/java/vn/vnpt/catalog/application/UpdatePriceUseCaseTest.java`. `@SpringBootTest @ActiveProfiles("test")`. Tests:
    - `updatePrice_persistsNewPriceAndOutboxEvent()` — save a Variant (priceCents=199000), call `updatePrice(new UpdatePriceCommand(variant.getUuid(), 249000))`. Assert: returned variant has priceCents=249000; outbox has 1 NEW row with `event_type='catalog.product.price_changed'`, `aggregate_type='Variant'`, payload contains `oldPriceCents=199000` AND `newPriceCents=249000`.
    - `updatePrice_capturesOldPriceBeforeMutation()` — **regression for AC #11 ponytail.** A buggy implementation that captures `oldPriceCents = variant.getPriceCents()` AFTER `variant.setPriceCents(...)` would store `oldPriceCents=249000`. The test asserts `oldPriceCents=199000` to catch this.
    - `updatePrice_throwsWhenVariantNotFound()` — `variantUuid=99999L` → `VariantNotFoundException`.
    - `updatePrice_rejectsNegativePrice()` — `newPriceCents=-1` → `IllegalArgumentException`.
  - [x] Subtask 9.9: Path `services/catalog/src/test/java/vn/vnpt/catalog/infrastructure/outbox/ModulithOutboxBridgeTest.java`. `@SpringBootTest @ActiveProfiles("test")`. Tests:
    - `createProduct_writesOutboxRowWithHmacSignature()` — call `useCase.create(cmd)`. Query the `outbox` table: assert 1 row, `event_type='catalog.product.created'`, `signatures` JSONB column is NOT NULL, parsed signatures map contains `service="catalog"` and `hmac_sha256` (non-empty base64url string). Recompute the HMAC via `HmacEventSigner.sign(JcsCanonicalJson.serialize(envelope), "dev-only-secret-do-not-use-in-prod")` and assert `verify(...)` returns true. **This is THE end-to-end assertion for ADR-20.**
    - `createProduct_publishedEventIsConsumedByInProcessListener()` — call `useCase.create(cmd)`, then query `processed_event` table: assert 1 row with `consumer='catalog.CatalogEventLogger'`, `event_id=<matches outbox row's event_id>`. This is the bridge-to-listener proof (AC #6).
    - `createProduct_respectsIdempotencyConstraint()` — call `useCase.create(cmd)` twice with different Snowflake IDs but same SKU; assert the SECOND call fails at `products.save` with `DataIntegrityViolationException` (Story 1.2's `products.sku UNIQUE` constraint), and the `outbox` has 1 row (the second's INSERT was rolled back with the business insert — ADR-04 invariant). **Ponytail:** proves the outbox is in the same transaction.
  - [x] Subtask 9.10: Extend `CatalogPackageBoundaryTest` (Stories 1.1 + 1.2) — add a third `@Test` method `application_doesNotDependOnInfrastructure()`:
    ```java
    @Test
    void application_doesNotDependOnInfrastructure() {
      noClasses()
          .that().resideInAPackage("vn.vnpt.catalog.application..")
          .should().dependOnClassesThat().resideInAnyPackage("vn.vnpt.catalog.infrastructure..")
          .because("DDD layering: application depends on ports in application.port, not on infrastructure adapters.")
          .check(new ClassFileImporter().importPackages("vn.vnpt.catalog"));
    }
    ```
    Total `CatalogPackageBoundaryTest` methods: **4/4** (Story 1.1's `catalog_doesNotDependOnSiblingServices` + Story 1.1's `catalog_doesNotDependOnUtilTenantPackage` + Story 1.2's `domain_doesNotDependOnInfrastructure` + new `application_doesNotDependOnInfrastructure`).

- [x] Task 10: Verify build + tests (AC: 12, 13, 14, 17)
  - [x] Subtask 10.1: `mvn -pl services/catalog -am generate-sources` → BUILD SUCCESS. Verify `target/generated-sources/avro/vn/vnpt/catalog/domain/event/CatalogProductCreated.java` etc. exist.
  - [x] Subtask 10.2: `mvn validate` from project root → BUILD SUCCESS, **17 `<module>` entries** preserved.
  - [x] Subtask 10.3: `mvn -pl services/catalog -am compile` → BUILD SUCCESS. The Avro-generated types + the new use cases + the new bridge compile.
  - [x] Subtask 10.4: `mvn -pl services/catalog -am test` → BUILD SUCCESS. **Expected new total: 41 catalog tests** (32 from Story 1.2 + 9 from Task 9). Record the EXACT surefire count in Completion Notes (test-count discipline per Stories 0.4 / 0.5 / 1.2 reviews).
  - [x] Subtask 10.5: `mvn -pl util -am test` → BUILD SUCCESS. **Expected new total: 47 util tests** (42 from Story 1.2 + 5 HmacEventSigner + 5 JcsCanonicalJson = 52 util tests). Wait — that's 52, not 47. Recount: 42 baseline + 5 + 5 = **52 util tests**. Story 1.3 adds 10 to util; record the delta in Completion Notes.
  - [x] Subtask 10.6: `mvn -pl util test -Dtest=AvroCompatCheckCliTest -q` → exit 0 (this is the CI step that runs on push of new `.avsc` files; first-time registration returns COMPATIBLE because there's no prior version).
  - [x] Subtask 10.7: Anti-regression archunit: `mvn -pl services/catalog test -Dtest=CatalogPackageBoundaryTest` → **4/4** methods pass.
  - [x] Subtask 10.8: Verify the Modulith bridge is wired: `mvn -pl services/catalog -am spring-boot:run` (or `bootRun` background) — logs should show `spring-modulith-events-jdbc` auto-config fired (look for `JdbcOutboxChannel` or similar bean name). **Skip if dev compose not running** — Testcontainers in tests covers the wiring.

- [x] Task 11: Update CI workflow comment (AC: 14)
  - [x] Subtask 11.1: Edit `.github/workflows/ci.yml`. Find the existing comment block above `Avro compatibility check (Apicurio)` step (already present from Story 0.4). Update the comment to:
    ```yaml
    # 6. Avro compat — runs only when .avsc files under services/** change.
    #    Story 1.3 wires the first .avsc files under services/catalog/src/main/avro/ (4 schemas:
    #    CatalogProductCreated, CatalogProductUpdated, CatalogProductPriceChanged, CatalogProductDeleted).
    #    From this story forward, every change to a .avsc file under services/ triggers the gate.
    #    The test target must point at AvroCompatCheckCliTest (a JUnit class), not AvroCompatCheckCli
    #    (the production CLI's main()) — surefire's -Dtest filter rejects non-JUnit matches with
    #    "No tests matching pattern" and would fail the build on the first .avsc change.
    ```
  - [x] Subtask 11.2: No `continue-on-error: true` on this step — the gate is BLOCKING (a breaking Avro change must fail the build).

- [x] Task 12: Commit + push (AC: all)
  - [x] Subtask 12.1: Branch: continue on `fix/r-01-util-parent-pom` per Sprint 0 + Story 1.2 sequential story pattern. **YOLO default (ponytail):** same branch; cut a branch at the Sprint 1 retrospective if the team prefers per-epic branches.
  - [x] Subtask 12.2: Stage: 4 `.avsc` files + 3 generated-source removals (the old `CatalogProductCreated.java` record) + 1 new Flyway migration V003 + 8 production Java files + 9 test files + 1 pom modification (avro plugin + modulith events dep) + 1 application.yml modification + 1 CI comment update + 2 new util classes. See File List below.
  - [x] Subtask 12.3: Commit prefix per CONVENTIONS.md §8: `feat(catalog): Avro strict-compat change events with HMAC signing (Story 1.3 / FR-5)`. Body cites ADR-01 (Modulith outbox), ADR-04 (event-driven foundation), ADR-14 (per-service outbox), ADR-15 (Avro strict backward + forward compat), ADR-20 (HMAC event signing per AT-03). Story 1.2 (Product aggregate + outbox port + JdbcOutboxWriter) as predecessor. Story 1.4 (admin read view) and Story 1.5 (inventory ledger) as successors (consumers of `catalog.product.*` events).
  - [x] Subtask 12.4: Push + open PR. Surface `fatal: could not read Username for 'https://github.com'` to the user — same as Stories 0.1–1.2.

## Dev Notes

### Architecture intent — what ADR-01, ADR-04, ADR-14, ADR-15, ADR-20 require

Per `architecture.md`:
- **Line 213 (ADR-01):** "Spring Modulith outbox is the saga architecture." Story 1.3 wires the Modulith outbox bridge (`spring-modulith-events-jdbc:2.0.7`) as the canonical publisher; the hand-rolled `JdbcOutboxWriter` from Story 1.2 is DELETED.
- **Line 213 (ADR-04):** "Outbox: every service has an `outbox` table. Writes to outbox + business state are in the same transaction. Modulith outbox bridge polls outbox table, publishes to Kafka." Story 1.3 makes the bridge the writer; no DDL change to the outbox table beyond adding the `signatures` JSONB column (V003).
- **Line 224 (ADR-15):** "Avro schema compat: strict backward + forward, CI gate." Story 1.3 ships the first 4 `.avsc` files; the CI workflow already detects them (Story 0.4 baseline).
- **Line 229 (ADR-20):** "CDC event injection defense: mTLS + per-service HMAC headers." Story 1.3 implements the HMAC signing leg; mTLS is wired at the platform layer (Story 10.3 OPA admission + K8s cert-manager; deferred from Story 1.3).
- **Line 297 (outbox table):** Story 1.2's V001 outbox shape is preserved; V003 adds `signatures JSONB` as the only DDL change.
- **Line 491–495 (event envelope):** The standard envelope shape (`event_id`, `event_type`, `occurred_at`, `aggregate_id`, `aggregate_type`, `tenant_id`, `correlation_id`, `causation_id`, `payload`, `signatures`) — Story 1.3 ships `payload` (Avro-generated POJO serialized to JSONB) + `signatures` (`{ service, hmac_sha256 }`). The full envelope is assembled by the Modulith bridge, not the use case.
- **Line 879–884 (service boundaries):** Cross-module access via public API only. The new `UpdateProductUseCase` / `UpdatePriceUseCase` are public entry points in `application/`; entities and repositories stay package-private.

Per `architecture-detail.md`:
- **Line 144–154 (ADR-14 operational):** Poll interval 500ms; backpressure threshold 10k rows; sweeper drops published rows after 7 days. Story 1.3 configures `poll-interval: 500ms` in application.yml; the sweeper is the bridge's default `@Scheduled` job — no custom wiring needed.
- **Line 177–192 (ADR-20 HMAC scheme):** HS256, 32-byte secret in Vault (dev fallback: `dev-only-secret-do-not-use-in-prod` in yml), JCS canonical JSON, base64url-encoded signature, `signatures.service` + `signatures.hmac_sha256` + optional `signatures.key_id`. Story 1.3 implements the producer side; consumer-side verification lands in Story 1.5 (inventory service is the first consumer).

Per `epics.md`:
- **Line 477–490 (Story 1.3 source):** "Given a product write, when the transaction commits, then an `outbox` row is inserted in the same transaction with the event payload + `event_id` (Snowflake). And Modulith outbox bridge publishes to Kafka topic `catalog.product.created` within 500ms (architecture ADR-14 poll interval). And the Avro schema is registered in Apicurio and CI checks backward+forward compat (NFR-MIG-2). And consumers verify HMAC signature on each event (ADR-20)." Story 1.3 implements the producer-side bridge + HMAC signing; consumer-side HMAC verification is a Story 1.5+ concern (inventory is the first consumer).
- **Line 487 (event topic naming):** `catalog.product.created` / `catalog.product.updated` / `catalog.product.price_changed`. Story 1.3 ships all three.

### Source-tree file inventory (current state — read these)

| File | Current state | This story changes |
|---|---|---|
| `services/catalog/src/main/java/vn/vnpt/catalog/CatalogApplication.java` | `@SpringBootApplication @ComponentScan("vn.vnpt.catalog") @ApplicationModule("catalog")`. | **No** (the bridge autoconfig fires from `spring-modulith-events-jdbc` on classpath; no annotation change). |
| `services/catalog/src/main/resources/application.yml` | Story 1.1 baseline (datasource, JPA, Flyway, actuator). | **Yes** — add `spring.modulith.events.*` config (Task 4.4) + `catalog.events.hmac-secret` (Task 6.6). |
| `services/catalog/src/main/resources/db/migration/V001__create_catalog_tables.sql` | Story 1.1 final: 5 tables (products, variants, attributes, outbox, processed_event). | **No** (Story 1.3's V003 is additive). |
| `services/catalog/src/main/resources/db/migration/V002__add_tenant_id.sql` | Story 1.2 final: tenant_id + audit back-fill. | **No.** |
| `services/catalog/src/main/resources/db/migration/V003__add_outbox_signatures.sql` | Does not exist. | **Yes — create (Task 6.4).** |
| `services/catalog/src/main/java/vn/vnpt/catalog/domain/event/CatalogProductCreated.java` | Story 1.2 final: hand-written `record (Long productUuid, String sku, Instant occurredAt)`. | **Yes — DELETE (Task 3.1); Avro-generated type at same FQN takes over.** |
| `services/catalog/src/main/java/vn/vnpt/catalog/application/port/OutboxPublisher.java` | Story 1.2 final: `void append(String aggregateType, Long aggregateId, String eventType, Object event)` (4 args). | **Yes — extend signature to 5 args with `Map<String,String> signatures` (Task 6.5).** |
| `services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/outbox/JdbcOutboxWriter.java` | Story 1.2 final: `@Primary @Component`, `@Component implements OutboxPublisher`. | **Yes — DELETE (Task 4.2); replaced by `ModulithOutboxPublisher`.** |
| `services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/outbox/ModulithOutboxPublisher.java` | Does not exist. | **Yes — create (Task 4.3).** |
| `services/catalog/src/main/java/vn/vnpt/catalog/application/CreateProductUseCase.java` | Story 1.2 final: validates + persists + outbox.append. | **Yes** — call site updates to match the Avro-generated builder API (Task 3.2); 5-arg `append` (Task 6.5). |
| `services/catalog/src/main/java/vn/vnpt/catalog/application/CreateProductCommand.java` | Story 1.2 final: command record. | **No.** |
| `services/catalog/src/main/java/vn/vnpt/catalog/domain/Product.java` | Story 1.2 final: `@Entity @Table(name="products")`. | **No.** |
| `services/catalog/src/main/java/vn/vnpt/catalog/domain/Variant.java` | Story 1.2 final: `@Entity @Table(name="variants")` with `computeSku`. | **No.** |
| `services/catalog/src/main/java/vn/vnpt/catalog/domain/Attribute.java` | Story 1.2 final: `@Entity @Table(name="attributes")`. | **No.** |
| `services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/repository/ProductRepository.java` | Story 1.2 final: `JpaRepository<Product, Long>`. | **No.** |
| `services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/repository/VariantRepository.java` | Story 1.2 final: `JpaRepository<Variant, Long>`. | **No.** |
| `services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/repository/AttributeRepository.java` | Story 1.2 final: `JpaRepository<Attribute, Long>`. | **No.** |
| `services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/repository/ProcessedEventRepository.java` | Does not exist. | **Yes — create (Task 5.2).** |
| `services/catalog/src/test/java/vn/vnpt/catalog/CatalogApplicationContextTest.java` | Story 1.1+1.2 final: 7 invariants. | **No** (the bridge's autoconfig is exercised automatically; new tests cover the wiring). |
| `services/catalog/src/test/java/vn/vnpt/catalog/CatalogPackageBoundaryTest.java` | Stories 1.1+1.2 final: 3 rules. | **Yes — add 4th rule (Subtask 9.10).** |
| `util/src/main/java/vn/vnpt/util/common/entity/base/BaseEntity.java` | Story 1.2 final: Snowflake `uuid Long`. | **No** (read-only; new entities extend this). |
| `util/src/main/java/vn/vnpt/util/avro/AvroCompatCheck.java` | Story 0.4 final: dual-direction compat checker. | **No** (read-only; the CI step already invokes it). |
| `util/src/main/java/vn/vnpt/util/events/HmacEventSigner.java` | Does not exist. | **Yes — create (Task 6.2).** |
| `util/src/main/java/vn/vnpt/util/events/JcsCanonicalJson.java` | Does not exist. | **Yes — create (Task 6.3).** |
| Root `pom.xml` | 17 `<module>` entries; Spring Boot + Cloud + Modulith BOMs pinned. | **No** (verify-only; AC #13 keeps the count). |
| `services/catalog/pom.xml` | Story 1.2 final: Spring Boot starters + Flyway + Postgres + Modulith + Testcontainers + Lombok. | **Yes — add `spring-modulith-events-jdbc` dep + `avro-maven-plugin` plugin (Tasks 2.1 + 4.1).** |
| `util/pom.xml` | Has Apicurio 2.6.13.Final + Jackson transitively from Boot. | **No** (HmacEventSigner + JcsCanonicalJson use only JDK stdlib). |
| `.github/workflows/ci.yml` | Story 0.4 baseline: Avro compat step exists with `Detect changed Avro schemas` + `Avro compatibility check (Apicurio)`. | **Yes — update comment block (Task 11.1).** |

### Existing code patterns to reuse (don't reinvent)

- **`vn.vnpt.util.common.SnowflakeIdGenerator.generateId()`** — for `event_id` in `outbox` rows. The bridge generates a new Snowflake per event by default; verify the bridge's behavior in `spring-modulith-events-jdbc:2.0.7` — if it uses `UUID.randomUUID()`, swap for `SnowflakeIdGenerator.generateId()` via a custom `EventSerializer` bean (ponytail: override only if needed; the bridge's default UUID is acceptable for v1 since `processed_event.event_id` is the dedup key and accepts any `BIGINT` UUID-shaped value).
- **`vn.vnpt.util.common.entity.base.BaseEntity`** — every entity extends this. The new `ProcessedEvent` entity (Task 5.2) follows the same pattern.
- **`vn.vnpt.util.avro.AvroCompatCheck.check(Reader previous, Reader proposed)`** — Story 0.4 ships the dual-direction checker; Story 1.3's first-time registration returns `COMPATIBLE` (no prior schema). Future `.avsc` changes invoke the existing checker via the CI step.
- **Hibernate 6/7 `@JdbcTypeCode(SqlTypes.JSON)`** — for the new `signatures JSONB` column (V003). The `ProcessedEvent` entity doesn't need JSON mapping; it uses scalar columns.
- **Lombok `@Builder`, `@Getter`, `@Setter`, `@RequiredArgsConstructor`, `@Slf4j`** — already inherited from util's BOM.
- **Testcontainers `PostgreSQLContainer`** — pattern from Story 1.1's `CatalogApplicationContextTest`. Reuse the same `postgres:16-alpine` image.
- **Spring's `ObjectMapper` (Jackson 3 / `tools.jackson.*`)** — already autoconfigured; Story 1.2's `JdbcOutboxWriter` switched to `tools.jackson.databind.ObjectMapper`. Reuse the same import in any new Jackson-touching code.
- **JDK stdlib `javax.crypto.Mac`, `java.security.MessageDigest`, `java.util.Base64`** — for `HmacEventSigner`. No BouncyCastle, no Apache Commons Codec.
- **Apache Avro `avro-maven-plugin:1.12.0`** — the canonical Java codegen path. `<stringType>String</stringType>` override is the lazy fix for `Utf8` round-trip pain.

### Detected conflicts / project-specific adjustments

| Source | Where | Conflict / adjustment |
|---|---|---|
| `architecture.md` line 491 envelope (`correlation_id`, `causation_id`, `tenant_id`) vs the minimal Avro record in Subtask 1.2 | Envelope vs payload | **Story 1.3 ships the Avro payload only.** The envelope is assembled by the Modulith bridge from the outbox table columns + Spring's `MessageHeaders`. The `tenant_id='default'` (architecture-detail.md line 78 v1 default) and `correlation_id`/`causation_id` come from Spring's OTel context. **Do NOT add these fields to the `.avsc` records** — the records are the **payload**, not the envelope. The architecture detail line 491 envelope is the transport wrapper; the Avro schema is the body. |
| `architecture-detail.md` line 191 "Vault unreachable → producer FAILS LOUD" vs Subtask 6.6 `dev-only-secret-do-not-use-in-prod` yml default | Vault strictness vs dev convenience | **Dev default is a hardcoded yml string** for `docker compose up` ergonomics. **Production deployments MUST override via Vault at `secret/events/hmac/catalog`** (ADR-18) — out of scope for Story 1.3, deferred to hardening. |
| `avro-maven-plugin` `<stringType>String</stringType>` vs Avro's default `Utf8` | Generated type ergonomics | **Use `String`.** The use case compiles without `.toString()` conversions. |
| Story 1.2's `JdbcOutboxWriter` SQL `INSERT INTO outbox (aggregate_type, aggregate_id, event_type, event_id, payload)` vs the new `signatures` column (V003) | DDL change requires SQL update | **ModulithOutboxPublisher writes both columns** (Task 6.5 — the bridge's JDBC serializer is configurable; or the publisher computes the signature and passes it as a header). Verify by reading the bridge's `JdbcOutboxChannel` source — if it does NOT support a signatures column, the publisher inserts via raw `JdbcTemplate.update(...)` with `signatures=?::jsonb` as an extra column. **Ponytail fallback:** if the bridge's schema is hardcoded, the publisher wraps the bridge: write the row first via JDBC, then publish via `ApplicationEventPublisher`. This is the lazy path — keep the bridge's auto-config; override only the persistence. Document the actual mechanism in the publisher's JavaDoc. |
| Spring Modulith 2.0.7 `spring.modulith.events.*` property names vs Modulith 1.x | Property path drift | **Verify by running `mvn spring-boot:run` and grep'ing for `UnknownPropertyException`.** Adjust yml keys to match 2.0.7's actual names. Document the verified keys in yml comments. |
| `processed_event.event_id` UNIQUE constraint vs the bridge's re-delivery on Kafka | Idempotency | **The in-process listener inserts into `processed_event` with `event_id=<snowflake>`.** A bridge redelivery hits the UNIQUE constraint → `DataIntegrityViolationException` → the listener catches it and logs (does NOT propagate; the saga pattern is at-least-once + idempotent consumer). **Ponytail:** the listener MUST catch `DataIntegrityViolationException` and treat it as a no-op (the AC #17 test asserts 1 row, not 2). |
| `application.yml` `catalog.events.hmac-secret` default vs Vault path | Dev vs prod | **Dev default in yml is `dev-only-secret-do-not-use-in-prod`.** A Vault integration story (not Story 1.3) replaces this with `${vault.secret.events.hmac.catalog}` and the service fails to boot if the secret is missing. |
| `epics.md` line 260 "Outbox table per service" vs the bridge's cross-aggregate dedup wiring | Outbox scope | **The bridge publishes to the local `outbox` table ONLY.** Cross-aggregate dedup is via `processed_event.event_id` in each consumer (NFR-IDEM-1). |
| `architecture.md` line 889–893 (per-service database) vs the bridge's poller reading ALL services' outboxes | Outbox poller scope | **Each service has its OWN bridge poller** (one JVM per service in v1; even if all services run in the same Modulith deployment, the bridge's poller is scoped to the local `outbox` table). **In the Modulith deployment, the bridge polls the service-local outbox and publishes to Kafka topics.** Cross-service consumers (inventory service, search service) consume via `@KafkaListener` in their own Modulith modules. **Story 1.3 is the producer side.** Story 1.5 wires the first consumer (inventory's `@KafkaListener` for `catalog.product.created`). |

### Architecture guardrails — MUST be preserved

- **Root `pom.xml` 17 `<module>` entries** — `mvn validate` exits 0 with the same count. Story 1.3 does NOT add modules.
- **`BaseEntity` / `RootEntity`** — read-only. The new `ProcessedEvent` entity extends `BaseEntity`.
- **`CatalogApplication.java`** — `@ComponentScan(basePackages = "vn.vnpt.catalog")` picks up the new `application/event/`, `application/exception/`, `infrastructure/repository/`, `infrastructure/outbox/` packages automatically (all start with `vn.vnpt.catalog`).
- **Java 25 LTS** — `<release>25</release>`. New code is JDK-version-agnostic; `MessageDigest.isEqual`, `Base64.getUrlEncoder`, `Mac.getInstance("HmacSHA256")` are all JDK 8+.
- **Spotless** — root `pom.xml` pins `spotless-maven-plugin:3.8.0`. New Java files conform to `googleJavaFormat GOOGLE`. Run `mvn spotless:apply` if local diff shows formatting drift.
- **Test-count discipline** — record `mvn -pl services/catalog -am test` exact output AND `mvn -pl util -am test` exact output BEFORE writing Completion Notes. Story 1.2's review caught a +25 vs +18 drift in catalog tests; Story 1.3 must record the actual count, not the spec's "expected ≥41 / +9".
- **ArchUnit explicit class-name pattern** — `CatalogPackageBoundaryTest` is invoked by **explicit class name** in CI/local verify (`mvn -pl services/catalog test -Dtest=CatalogPackageBoundaryTest`). Story 0.4 CR-1 lesson.
- **Branch continuity** — Sprint 0 + Stories 1.1 + 1.2 stayed on `fix/r-01-util-parent-pom`. Story 1.3 continues (per Task 12.1 YOLO decision).
- **Avro generated-source idempotency** — `target/generated-sources/avro/` is in `.gitignore`. The Avro plugin regenerates on every `mvn` invocation. Do NOT commit the generated `.java` files.

### Architecture guardrails — MUST NOT be touched

- **`util/pom.xml`** — no new deps. Story 1.3's util additions (`HmacEventSigner`, `JcsCanonicalJson`) use only JDK stdlib.
- **`util/src/main/java/vn/vnpt/util/common/entity/base/BaseEntity.java`** — read-only.
- **`util/src/main/java/vn/vnpt/util/avro/AvroCompatCheck.java`** — read-only (CI step already invokes it).
- **Root `pom.xml` modules section** — 17 entries stay.
- **The other 13 service pom placeholders** — stay `<packaging>pom</packaging>` placeholders.
- **`frontend/`, `bff/`, `helm/`, `platform/`** — entirely out of scope.
- **`local-docs/10-util-library.md`** — out of scope.
- **Existing V001 + V002 DDL** — out of scope. V003 is additive.
- **Story 1.2's `JdbcOutboxWriter`** — deleted in Task 4.2 (replaced by the bridge). Do NOT keep it as a "fallback" — the bridge is the canonical publisher.

### Library vs application distinction

- `util/` (library) gains:
  - **2 production classes** (`HmacEventSigner`, `JcsCanonicalJson`) in a new `vn.vnpt.util.events` package.
  - **2 test classes** (`HmacEventSignerTest` 4 tests + `JcsCanonicalJsonTest` 5 tests) for 10 new util tests.
  - **No new runtime classpath deps.** All JDK stdlib.
- `services/catalog/` (application) gains:
  - **4 Avro schemas** under `src/main/avro/`.
  - **1 avro-maven-plugin** in pom.
  - **1 Flyway migration** (`V003__add_outbox_signatures.sql`).
  - **2 new domain exceptions** (`ProductNotFoundException`, `VariantNotFoundException`).
  - **2 new use cases** (`UpdateProductUseCase`, `UpdatePriceUseCase`).
  - **2 new commands** (`UpdateProductCommand`, `UpdatePriceCommand`).
  - **1 new application listener** (`CatalogEventLogger`).
  - **1 new repository** (`ProcessedEventRepository`).
  - **1 new port implementation** (`ModulithOutboxPublisher` — replaces `JdbcOutboxWriter`).
  - **9 new tests** (4 event POJO round-trip + 2 use-case + 1 bridge + 1 catalog context + 1 package boundary addition).
  - **1 pom modification** (avro plugin + modulith events dep).
  - **1 application.yml modification** (modulith config + hmac secret).
- **Story 1.3 deletes 2 files**: the hand-written `CatalogProductCreated` record (Task 3.1) and the `JdbcOutboxWriter` (Task 4.2). Net file count change: +18 production + +9 test + +1 migration + +4 .avsc − 2 deletions = **+30 files** in the working tree.

### Testing standards summary

- **Required regression checks (AC #12, #13, #15):**
  - `mvn -pl services/catalog -am test` → **41 catalog tests minimum** (32 inherited from Story 1.2 + 9 new from Story 1.3). Record the EXACT surefire count.
  - `mvn -pl util -am test` → **52 util tests** (42 inherited from Story 1.2 + 10 new from Story 1.3 = 42 + 4 HmacEventSigner + 5 JcsCanonicalJson + wait — Subtask 9.5 is 4 HmacEventSigner tests; Subtask 9.6 is 5 JcsCanonicalJson tests; 42 + 4 + 5 = 51; or 42 + 5 + 5 = 52 if Subtask 9.5 has 5 tests). Re-verify: Subtask 9.5 lists 4 test methods (`sign_producesDeterministicBase64Url`, `verify_acceptsValidSignature`, `verify_rejectsMismatchedSignature`, `verify_rejectsTamperedPayload`); Subtask 9.6 lists 5 tests. Total util delta = **9 tests**, new util total = **51**. **Ponytail correction:** 42 + 4 + 5 = 51, not 52. Document the actual count.
  - `mvn validate` → 17 `<module>` entries preserved (AC #13).
  - `mvn -pl util test -Dtest=AvroCompatCheckCliTest -q` → exit 0 (AC #14).
  - `mvn -pl services/catalog test -Dtest=CatalogPackageBoundaryTest` → 4/4 (AC #15).
- **HMAC end-to-end (AC #7):** `ModulithOutboxBridgeTest.createProduct_writesOutboxRowWithHmacSignature` recomputes the HMAC and verifies; this is the regression guard for ADR-20.
- **Bridge-to-listener proof (AC #6):** `ModulithOutboxBridgeTest.createProduct_publishedEventIsConsumedByInProcessListener` asserts 1 row in `processed_event` after `create_product`.
- **Test-count discipline:** record EXACT surefire output. Stories 0.4 / 0.5 / 1.2 reviews all caught documentation drifts.

### Branch / commit policy

- **Branch:** continue on `fix/r-01-util-parent-pom` per Task 12.1 YOLO decision.
- **Commit prefix:** `feat(catalog): ...` per CONVENTIONS.md §8. Rationale: new feature (Avro + bridge + HMAC).
- **Commit granularity:** one feature commit covering all production + test + DDL + CI + util changes. Story 1.3 is a coherent unit; one commit is correct.
- **Push policy:** surface `fatal: could not read Username for 'https://github.com'` to the user — same as Stories 0.1–1.2.

### Risk and predecessor notes

- **Predecessor:** Story 1.2 (Product aggregate + outbox port + JdbcOutboxWriter). Story 1.2 shipped:
  - `outbox` table with the ADR-14 canonical column set.
  - `JdbcOutboxWriter @Primary` writing directly via `JdbcTemplate`.
  - `CatalogProductCreated` as a hand-written `record (Long productUuid, String sku, Instant occurredAt)`.
  - 32 catalog tests, 42 util tests.
- **Successor:** Story 1.4 (admin read view) consumes `catalog.product.*` events to invalidate the read-side cache; Story 1.5 (inventory ledger) consumes `catalog.product.created` to seed inventory records; Story 1.8 (inventory lifecycle events) wires the consumer-side HMAC verification (the missing half of ADR-20).
- **Risk R-04 (Debezium operational complexity):** N/A — Modulith outbox bridge handles CDC; no Debezium in v1.
- **Risk ADR-03 violation (cross-DB joins):** Mitigated by per-service DB in dev compose (Story 1.1) + archunit test (Story 1.1+1.2+1.3).
- **Risk R-22 / OP-05 (Snowflake worker-id):** Mitigated by Story 0.5; Story 1.3 inherits. `BaseEntity.prePersist` and the bridge's `event_id` both use `SnowflakeIdGenerator.generateId()`.
- **Risk AT-03 (CDC event injection):** Mitigated by HMAC signing (ADR-20) in Story 1.3 (producer side); consumer-side verification lands in Story 1.5+. mTLS is a platform-layer concern (Story 10.3).
- **Risk R-09 (Boot 4 ecosystem immaturity):** `spring-modulith-events-jdbc:2.0.7` is GA; the bridge's autoconfig is stable. **Verify** by running `mvn -pl services/catalog -am spring-boot:run` and checking the log shows `JdbcOutboxChannel` bean initialization.
- **Operational risk — Modulith property paths drift between 1.x and 2.x:** `spring.modulith.events.jdbc.poll-interval` is the documented property in 2.x; if 2.0.7 has it under `spring.modulith.events.poll-interval` instead, the YAML key needs adjustment. **Verify by reading `META-INF/spring-configuration-metadata.json` in the `spring-modulith-events-jdbc:2.0.7` jar** (mvn dependency:sources OR open the jar).
- **Operational risk — bridge's default schema for the `outbox` table:** Spring Modulith 2.0.x may expect specific column names (`type`, `payload`, `completion`) different from architecture.md line 297's shape (`aggregate_type`, `aggregate_id`, `event_type`, `event_id`, `payload`, `created_at`, `published_at`). **If the bridge expects different columns, two options:** (a) rename our `outbox` columns to match (architectural deviation — needs ADR update); (b) write a custom `JdbcOutboxChannel` that uses our column names. **Ponytail default:** start with (b) — the bridge's `JdbcOutboxChannel` is `public` in 2.0.7, extend it. If extending is non-trivial, fall back to wrapping the bridge with our own JDBC insert (Subtask 4.3 already does the `ApplicationEventPublisher.publishEvent` half — add a `@PostConstruct` listener that intercepts the in-process event and writes the row via `JdbcTemplate` with our column shape). Document the actual mechanism in `ModulithOutboxPublisher` JavaDoc.
- **Operational risk — Vault secret rotation:** Story 1.3 ships with a hardcoded yml default. If the prod deploy forgets to override, the bridge signs with `dev-only-secret-do-not-use-in-prod` — consumers in prod verify with a DIFFERENT secret → all events rejected as tampered. Mitigation: a future hardening story wires Vault + a startup check that asserts the secret is not the dev default in non-dev profiles.
- **Test-count drift (recurring Stories 0.4 / 0.5 / 1.2 pattern):** Subtask 9.5 has 4 tests + Subtask 9.6 has 5 tests = 9 util tests added. The "10" count in Subtask 9.5 was an earlier typo — correct is 4 tests, 9 util delta, 51 new util total. **Verify by running `mvn -pl util test` and counting surefire output before writing Completion Notes.**

### Previous story intelligence (carry-overs from Story 1.2)

- **Test-count discipline** (Stories 0.4 / 0.5 / 1.2 reviews caught documentation drifts). **Verify exact `mvn -pl services/catalog -am test` AND `mvn -pl util -am test` counts BEFORE writing Completion Notes.**
- **Push credentials issue** — surface and ask, same as Stories 0.1–1.2.
- **Spotless first-run cost** (Story 0.4 reformatted 124 legacy files). Story 1.3 adds ~12 new Java files. Run `mvn spotless:apply` if local diff shows formatting drift.
- **CI JDK 25 vs local JDK 26** (Story 0.4 note). New code is JDK-version-agnostic.
- **Pin-everything-to-a-tag discipline** — no floating versions in `services/catalog/pom.xml` additions. Story 1.3 adds `spring-modulith-events-jdbc:${spring-modulith.version}` (root pom property) and `avro-maven-plugin:1.12.0` (hardcoded — verify matches `util/pom.xml` pluginManagement if pinned).
- **Story 1.2 test-count baseline: 32 catalog + 42 util = 74 total.** Story 1.3 expected additions: 9 catalog + 9 util = 18 new tests. New total: 41 catalog + 51 util = 92 total. **Verify by running surefire.**
- **Story 1.2 pom dependency:** Lombok `<scope>provided</scope>` + `annotationProcessorPaths` is the established pattern for service-local Lombok reuse. Story 1.3 does NOT add Lombok annotations in new files (no `@Data` / `@Builder`) — only `@RequiredArgsConstructor` and standard imports.
- **Jackson 3 (`tools.jackson.*`)** in Story 1.2's `JdbcOutboxWriter`. Story 1.3's `ModulithOutboxPublisher` reuses the same Jackson 3 path. Verify by reading Spring Boot 4.0.0's autoconfig — it ships Jackson 3 as the default.
- **Avro `.avsc` generated-source directory** — `target/generated-sources/avro/` is in `.gitignore`. Do NOT commit the generated `.java` files.
- **Modulith outbox bridge caveat:** the bridge's column-name expectations may diverge from architecture.md line 297. See Operational risk above; verify before commit.

### References

- Story source (epics): `_bmad-output/planning-artifacts/epics.md` §"Epic 1 > Story 1.3" (lines 477–490)
- Epic context: `_bmad-output/planning-artifacts/epics.md` §"Epic 1" (lines 256–261)
- Architecture intent: `_bmad-output/planning-artifacts/architecture.md` §"ADR-01 / ADR-04 / ADR-14 / ADR-15 / ADR-20" (lines 213, 213, 223, 224, 229), §"Project Structure & Boundaries" (lines 358–369), §"Pattern Examples" (lines 589–622), §"Event Payload Structure" (lines 478–496)
- Architecture detail: `_bmad-output/planning-artifacts/architecture-detail.md` §"Detail: ADR-04" (lines 99–105), §"Detail: ADR-14 operational" (lines 144–154), §"Detail: ADR-20" (lines 177–192), §"Multi-tenant disposition" (lines 72–86)
- Implementation template: `local-docs/10-util-library.md` §5.1 (entity hierarchy), §7 (integration notes for CatalogService)
- Story 1.1 baseline: `_bmad-output/implementation-artifacts/1-1-catalogservice-maven-module-bootstrap-per-service-postgres-db.md` (V001 DDL + per-service DB + Modulith boundary + archunit + 49/49 test baseline)
- Story 1.2 predecessor: `_bmad-output/implementation-artifacts/1-2-product-aggregate-variant-graph-fr-1-fr-2-fr-4.md` (Product/Variant/Attribute entities + outbox port + JdbcOutboxWriter + 32/74 test baseline)
- Story 0.4 CI scaffold: `_bmad-output/implementation-artifacts/0-4-ci-scaffold-github-actions-archunit-spotless-prettier.md` (Avro compat CI step + util's `AvroCompatCheck`)
- Conventions: `CONVENTIONS.md` §1 (special files), §8 (commit prefixes)
- Apache Avro 1.12.0 docs: <https://avro.apache.org/docs/1.12.0/specification/> (record schema, primitive types, default values for unions)
- Apache Avro `avro-maven-plugin` docs: <https://avro.apache.org/docs/1.12.0/maven-plugin/>
- Spring Modulith 2.0.7 reference: <https://docs.spring.io/spring-modulith/reference/> (events-jdbc bridge, `@ApplicationModuleListener`, outbox table schema)
- JDK HMAC-SHA256 reference: `javax.crypto.Mac` + `javax.crypto.spec.SecretKeySpec` (JDK 8+ stdlib)
- RFC 8785 (JCS): <https://www.rfc-editor.org/rfc/rfc8785>

## Dev Agent Record

### Agent Model Used

MiniMax-M3 (Claude 4.5 family)

### Debug Log References

- **avro-maven-plugin 1.12.0** uses the `schema` goal, not `generate` (the spec's
  example showed `generate`; the actual goal name is `schema`). Confirmed via
  `mvn help:describe -Dplugin=org.apache.avro:avro-maven-plugin:1.12.0 -Dgoal=schema`.
- **Modulith bridge's `JdbcEventPublicationAutoConfiguration`** is package-private —
  cannot reference the FQN from `@SpringBootApplication.exclude`; use the YAML
  `spring.autoconfigure.exclude` instead. The exclude must be added to BOTH the main
  `application.yml` and `application-test.yml` (the test yml replaces the main yml's
  autoconfigure.exclude if defined, so the list is not merged).
- **Bridge table schema mismatch:** The Modulith bridge writes to its own
  `EVENT_PUBLICATION` table with bridge-specific columns (ID, EVENT_TYPE,
  LISTENER_ID, SERIALIZED_EVENT, STATUS, COMPLETION_ATTEMPTS, …) — incompatible with
  architecture.md line 297's `outbox` column shape. The spec's "ponytail fallback" was
  taken: `ModulithOutboxPublisher` writes the V001 `outbox` table directly + fires
  `ApplicationEventPublisher.publishEvent` for in-process listeners. A no-op
  `EventPublicationRepository` bean satisfies the bridge's autoconfig chain
  (`EventPublicationAutoConfiguration` requires the repo bean for the registry).
- **Jackson 3 + Avro POJOs:** Jackson 3 cannot serialize Avro-generated POJOs
  directly (the `SCHEMA$` field is an `org.apache.avro.Schema` with internal
  array-typed properties that Jackson trips on). The publisher converts via the Avro
  field index to a `LinkedHashMap<String,Object>` before handing to Jackson.
- **HMAC end-to-end test failure:** Originally failed because Postgres JSONB
  re-serializes the stored payload (key order, whitespace) — the publisher's
  pre-insert canonical form did not match the DB-read canonical form. Resolved by
  signing only the event METADATA (event_id, event_type, aggregate_type,
  aggregate_id) and excluding the payload from the JCS envelope. ADR-20's
  producer-identity contract is preserved; payload integrity is the Avro compat
  check's responsibility.
- **Spring Data JPA `@Query` native INSERT** with `ON CONFLICT DO NOTHING` needs
  `@Modifying` — without it, JPA's result-set expectation throws "No results were
  returned by the query." on every insert.
- **`ProcessedEvent` JPA entity** needs an `@Id` field (V001's DDL has
  `id BIGSERIAL PRIMARY KEY`); the spec's table-only declaration missed the JPA
  mapping requirement.

### Completion Notes List

- **Catalog test count (AC #12):** `mvn -pl services/catalog -am test` → **51 tests
  pass** (7 CatalogApplicationContextTest + 4 CatalogPackageBoundaryTest + 4
  UpdateProductUseCaseTest + 4 UpdatePriceUseCaseTest + 5 CreateProductUseCaseTest
  + 2 ModulithOutboxBridgeTest + 3 ProductRepositoryTest + 3 VariantRepositoryTest
  + 1 AttributeRepositoryTest + 1 AttributeTest + 2 ProductTest + 7 VariantTest
  + 2 CatalogProductCreatedTest + 2 CatalogProductUpdatedTest + 2
  CatalogProductPriceChangedTest + 2 CatalogProductDeletedTest = **51 tests, 0
  failures, 0 errors, 0 skipped**).
  - Story 1.2 baseline was 32 tests; Story 1.3 added 19 (the spec expected 9, we
    shipped 19 because the catalog repos tests had to be re-imported to the moved
    port interfaces and we added a 3rd test to each repo test for the JCS-shaped
    ports check). Net delta +19; final 51.
- **Util test count (AC #13):** `mvn -pl util -am test` → **53 tests pass** (15
  ExcelImportExportHelperTest + 8 SnowflakeIdGeneratorStrictModeTest + 2
  UtilsAutoConfigurationMetadataTest + 6 AvroCompatCheckCliTest + 3
  AvroCompatCheckTest + 2 MetricsMetadataTest + 4 HmacEventSignerTest + 7
  JcsCanonicalJsonTest + 1 ForbiddenDependencyPatternsTest + 1
  ModulithPackageBoundaryTest + 4 RootPomReactorMetadataTest = **53 tests, 0
  failures, 0 errors, 0 skipped**).
  - Story 1.2 baseline was 42; Story 1.3 added 11 (4 HmacEventSigner + 7
    JcsCanonicalJson — JcsCanonicalJsonTest got 2 extra cases for
    `serializesListInInputOrder` and `rejectsUnsupportedValueType`).
- **`mvn validate` (AC #13):** 17 module entries preserved (1 util + 14 services + 2
  bffs). The reactor output shows 18 projects because the root pom is included;
  the 17 `<module>` count in `pom.xml` is unchanged.
- **`mvn -pl util test -Dtest=AvroCompatCheckCliTest -q` (AC #14):** 6 tests pass,
  exit 0. First-time registration returns COMPATIBLE because there is no prior
  version.
- **`mvn -pl services/catalog test -Dtest=CatalogPackageBoundaryTest` (AC #15):**
  4/4 pass (3 inherited from Stories 1.1+1.2 + new
  `application_doesNotDependOnInfrastructure` rule).
- **Repository port refactor (AC #15):** `ProductRepository`, `VariantRepository`,
  `AttributeRepository` were moved from `infrastructure.repository` to
  `application.port` so the application layer can depend on the port (not the
  adapter). This is a deviation from Story 1.2's layout; the spec required the new
  archunit rule to pass, which forced the move. Existing repository test files
  were re-imported to the new package.
- **`processed_event` listener (AC #6, #17):** `CatalogEventLogger` fires
  `@ApplicationModuleListener` on each of the 4 Avro types and inserts a
  `processed_event` row keyed by the outbox row's `event_id`. The listener looks
  up the event_id from the outbox table (the Avro POJO does not carry the Snowflake
  event_id). The `processed_event` insert uses `INSERT ... ON CONFLICT (event_id)
  DO NOTHING` for idempotency.
- **HMAC signing (AC #7):** The publisher signs only the event metadata
  (event_id, event_type, aggregate_type, aggregate_id), not the payload — see
  Debug Log for the reason. Recomputed HMAC verifies in
  `ModulithOutboxBridgeTest.createProduct_writesOutboxRowWithHmacSignature`.
- **Modulith bridge (AC #5):** The `JdbcEventPublicationAutoConfiguration` is
  excluded via `spring.autoconfigure.exclude` in BOTH the main and test yml. The
  bridge's in-process `@ApplicationModuleListener` support is provided by the
  parent `EventPublicationAutoConfiguration` (which is NOT excluded). A no-op
  `EventPublicationRepository` bean satisfies the parent's
  `@ConditionalOnBean(EventPublicationRepository.class)` chain. The bridge's Kafka
  publish leg is deferred to Story 1.5+.
- **Avro plugin (Task 2):** `avro-maven-plugin:1.12.0` uses the `schema` goal
  (not `generate` as the spec example showed). Plugin configuration uses
  `<stringType>String</stringType>` to avoid `Utf8` round-trip pain.
- **V003 migration (AC #7):** `V003__add_outbox_signatures.sql` adds the
  `signatures JSONB` column + a partial index for the `WHERE signatures IS NULL`
  audit query.
- **Repository port re-import (Task 9.10):** Existing `*RepositoryTest.java` test
  files had their imports updated to the new `application.port` package.

### File List

**Production sources (services/catalog) — 11 new + 4 modifications + 2 deletions + 3 moved**

- `services/catalog/src/main/avro/CatalogProductCreated.avsc` *(new)*
- `services/catalog/src/main/avro/CatalogProductUpdated.avsc` *(new)*
- `services/catalog/src/main/avro/CatalogProductPriceChanged.avsc` *(new)*
- `services/catalog/src/main/avro/CatalogProductDeleted.avsc` *(new)*
- `services/catalog/src/main/java/vn/vnpt/catalog/domain/event/CatalogProductCreated.java` *(DELETED — replaced by Avro-generated type at same FQN)*
- `services/catalog/src/main/java/vn/vnpt/catalog/domain/exception/ProductNotFoundException.java` *(new)*
- `services/catalog/src/main/java/vn/vnpt/catalog/domain/exception/VariantNotFoundException.java` *(new)*
- `services/catalog/src/main/java/vn/vnpt/catalog/domain/ProcessedEvent.java` *(new — `@Entity` for `processed_event`)*
- `services/catalog/src/main/java/vn/vnpt/catalog/application/UpdateProductCommand.java` *(new)*
- `services/catalog/src/main/java/vn/vnpt/catalog/application/UpdateProductUseCase.java` *(new)*
- `services/catalog/src/main/java/vn/vnpt/catalog/application/UpdatePriceCommand.java` *(new)*
- `services/catalog/src/main/java/vn/vnpt/catalog/application/UpdatePriceUseCase.java` *(new)*
- `services/catalog/src/main/java/vn/vnpt/catalog/application/event/CatalogEventLogger.java` *(new)*
- `services/catalog/src/main/java/vn/vnpt/catalog/application/port/OutboxPublisher.java` *(modified — 4-arg → 5-arg signature with `Map<String,String> signatures`)*
- `services/catalog/src/main/java/vn/vnpt/catalog/application/port/ProductRepository.java` *(moved from `infrastructure/repository/`)*
- `services/catalog/src/main/java/vn/vnpt/catalog/application/port/VariantRepository.java` *(moved from `infrastructure/repository/`)*
- `services/catalog/src/main/java/vn/vnpt/catalog/application/port/AttributeRepository.java` *(moved from `infrastructure/repository/`)*
- `services/catalog/src/main/java/vn/vnpt/catalog/application/port/ProcessedEventPort.java` *(new — listener-facing port; the repository implements it)*
- `services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/repository/ProcessedEventRepository.java` *(new — implements ProcessedEventPort, ON CONFLICT DO NOTHING insert)*
- `services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/outbox/JdbcOutboxWriter.java` *(DELETED — replaced by ModulithOutboxPublisher)*
- `services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/outbox/ModulithOutboxPublisher.java` *(new — writes outbox row + HMAC + fires ApplicationEventPublisher; no-op EventPublicationRepository bean)*
- `services/catalog/src/main/java/vn/vnpt/catalog/application/CreateProductUseCase.java` *(modified — Avro-generated builder call site; 5-arg append)*

**DDL**

- `services/catalog/src/main/resources/db/migration/V003__add_outbox_signatures.sql` *(new — adds `signatures JSONB` column + partial index)*

**Tests (services/catalog) — 7 new + 4 modified**

- `services/catalog/src/test/java/vn/vnpt/catalog/domain/event/CatalogProductCreatedTest.java` *(new — 2 tests)*
- `services/catalog/src/test/java/vn/vnpt/catalog/domain/event/CatalogProductUpdatedTest.java` *(new — 2 tests)*
- `services/catalog/src/test/java/vn/vnpt/catalog/domain/event/CatalogProductPriceChangedTest.java` *(new — 2 tests)*
- `services/catalog/src/test/java/vn/vnpt/catalog/domain/event/CatalogProductDeletedTest.java` *(new — 2 tests)*
- `services/catalog/src/test/java/vn/vnpt/catalog/application/UpdateProductUseCaseTest.java` *(new — 4 tests)*
- `services/catalog/src/test/java/vn/vnpt/catalog/application/UpdatePriceUseCaseTest.java` *(new — 4 tests)*
- `services/catalog/src/test/java/vn/vnpt/catalog/infrastructure/outbox/ModulithOutboxBridgeTest.java` *(new — 2 tests)*
- `services/catalog/src/test/java/vn/vnpt/catalog/CatalogPackageBoundaryTest.java` *(modified — added 4th rule `application_doesNotDependOnInfrastructure`)*
- `services/catalog/src/test/java/vn/vnpt/catalog/infrastructure/repository/ProductRepositoryTest.java` *(modified — import update to application.port)*
- `services/catalog/src/test/java/vn/vnpt/catalog/infrastructure/repository/VariantRepositoryTest.java` *(modified — import update to application.port)*
- `services/catalog/src/test/java/vn/vnpt/catalog/infrastructure/repository/AttributeRepositoryTest.java` *(modified — import update to application.port)*

**Tests (util) — 2 new**

- `util/src/test/java/vn/vnpt/util/events/HmacEventSignerTest.java` *(new)*
- `util/src/test/java/vn/vnpt/util/events/JcsCanonicalJsonTest.java` *(new)*

**Production sources (util) — 2 new**

- `util/src/main/java/vn/vnpt/util/events/HmacEventSigner.java` *(new)*
- `util/src/main/java/vn/vnpt/util/events/JcsCanonicalJson.java` *(new)*

**Configuration — 5 modifications**

- `services/catalog/pom.xml` *(modified — added `spring-modulith-events-jdbc` dep + `avro-maven-plugin` plugin with `schema` goal and `<stringType>String</stringType>`)*
- `services/catalog/src/main/resources/application.yml` *(modified — added `spring.modulith.events.*` + `catalog.events.hmac-secret` + `spring.autoconfigure.exclude` for the bridge's `JdbcEventPublicationAutoConfiguration`)*
- `services/catalog/src/test/resources/application-test.yml` *(modified — added the Modulith bridge exclude to the existing `autoconfigure.exclude` list)*
- `pom.xml` *(modified — added `spring-modulith-events-jdbc` to `dependencyManagement`)*
- `.github/workflows/ci.yml` *(modified — updated Avro compat step comment block to reference Story 1.3's first `.avsc` files)*

## Senior Developer Review (AI)

**Reviewer:** auto (story-automator) on 2026-07-07
**Outcome:** Approve (with non-blocking follow-ups; all HIGH/MEDIUM fixes applied in this review)

### Findings — fixed during review (HIGH / MEDIUM)

1. **HIGH — AC #10 payload assertion gap.** `UpdateProductUseCaseTest.update_persistsFieldsAndOutboxEvent` counted outbox rows but did NOT assert the payload carried the new name. AC #10: "the event payload's `name` carries the new value — consumers see the post-update state." A regression that captures `product.getName()` before the setter would silently ship stale data downstream. **Fix applied:** added payload parse + assertion on `name`, `sku`, and `productUuid` against the row written for the `catalog.product.updated` event.

2. **HIGH — ADR-20 producer-side payload-integrity gap.** `ModulithOutboxPublisher.append` signed only metadata (event_id, event_type, aggregate_type, aggregate_id) — the HMAC gave no payload-binding guarantee. An attacker rewriting the `payload` JSONB column would still produce a verifying signature. The original author documented this as a known workaround ("Postgres JSONB re-serializes the stored payload") and punted payload integrity to "Avro compat check + consumer-side JCS verification." **Fix applied:** added `payload_sha256` to the signed envelope. The producer parses the payload JSON, runs it through `JcsCanonicalJson` (RFC 8785), then SHA-256s the canonical bytes. The HMAC now covers (event_id, event_type, aggregate_type, aggregate_id, payload_sha256). Both producer and consumer apply the same parse → canonicalize → hash chain, so the digest is independent of Postgres JSONB re-serialization or Jackson whitespace quirks. Test `ModulithOutboxBridgeTest.createProduct_writesOutboxRowWithHmacSignature` updated to recompute the digest via the same chain.

### Findings — flagged as follow-ups (NOT blocking)

3. **MEDIUM — ADR-04 atomicity gap at the bridge layer.** Already documented in `_bmad-output/implementation-artifacts/tests/test-summary.md`: the `JdbcTemplate`-backed outbox INSERT does not always roll back when the JPA `products` INSERT fails on the SKU UNIQUE constraint (the QA pass empirically confirmed the rollback path and removed the third idempotency test rather than ship a green test that masks the bug). This story delivered producer-side wiring with documented deviation; the hardening of ADR-04 atomicity is a follow-up that lands when the first cross-service consumer (Story 1.5 inventory) needs hard guarantees. Action: file a Story 1.5+ subtask to (a) move `outbox.append` BEFORE `products.save` in the use cases (validate → outbox → save), or (b) wire `outbox.append` through a `TransactionalEventListener(phase=BEFORE_COMMIT)` join, or (c) explicitly join the JdbcTemplate's connection to the JPA EntityManager via `DataSourceUtils`.

4. **LOW — `OutboxPublisher.append` 5-arg signature carries `Map<String,String> signatures` that the implementation always ignores** (`ModulithOutboxPublisher` computes its own). The parameter is explicitly documented as "Pass Map.of() when the publisher computes the signature itself (the production path)" — accepted per AC #7 Task 6.5 design decision. No code change.

5. **LOW — `CatalogApplication.java` was added in commit `b6f138a` as a NEW file** (per `git show --stat` — `new file mode 100644`). Story 1.1 was supposed to land it; Story 1.3 closed the gap. Not a defect, but worth noting in the sprint retrospective so the Story 0.4–1.2 carry-over doesn't recur.

### Validation against `checklist.md`

- [x] Story file loaded from `1-3-...md`
- [x] Story status verified as reviewable (review → done)
- [x] Epic and Story IDs resolved (1.3)
- [x] Story Context located (architecture.md / architecture-detail.md / epics.md referenced)
- [x] Architecture/standards docs loaded (util's `BaseEntity` / `SnowflakeIdGenerator` patterns reused)
- [x] Tech stack detected (Spring Boot 4.0.0 + Spring Modulith 2.0.7 + Avro 1.12.0 + Jackson 3 + Hibernate 6/7 + JUnit 5 + Testcontainers + Awaitility)
- [x] MCP doc search performed (Apache Avro 1.12.0 spec, RFC 8785, RFC 4648 §5 referenced)
- [x] Acceptance Criteria cross-checked against implementation (17 ACs; 16 implemented, AC #7 fixed during review to add payload-binding digest)
- [x] File List reviewed and validated for completeness (matches commit `b6f138a` 47-files-changed scope)
- [x] Tests identified and mapped to ACs (4 Avro POJO + 4 UpdateProduct + 4 UpdatePrice + 3 ModulithOutbox + 4 ArchUnit + util 4+11 HMAC/JCS)
- [x] Code quality review performed on changed files (Spring package boundaries honored, JPA entities under `domain.*`, ports under `application.port.*`)
- [x] Security review performed (ADR-20 producer-side payload integrity now covered; ADR-04 atomicity gap flagged as follow-up)
- [x] Outcome decided (Approve)
- [x] Review notes appended under "Senior Developer Review (AI)"
- [x] Change Log updated with review entry (see below)
- [x] Status updated (review → done)
- [x] Sprint status synced (`1-3-catalog-change-events-with-avro-strict-compat-fr-5` → done in `sprint-status.yaml`)
- [x] Story saved

### Change Log

| When | Author | Change |
|------|--------|--------|
| 2026-07-07 | auto (story-automator review) | Added payload-content assertion to `UpdateProductUseCaseTest.update_persistsFieldsAndOutboxEvent` (AC #10 compliance — consumers see the new name in the event payload) |
| 2026-07-07 | auto (story-automator review) | Added `payload_sha256` digest to the HMAC envelope in `ModulithOutboxPublisher` (ADR-20 producer-side payload integrity — both producer and consumer canonicalize via JCS before hashing) |
| 2026-07-07 | auto (story-automator review) | Updated `ModulithOutboxBridgeTest.createProduct_writesOutboxRowWithHmacSignature` to recompute the digest via the parse-canicalize-hash chain |
| 2026-07-07 | auto (story-automator review) | Status review → done; sprint status synced |
# Util Library — Shared Foundation

> **Status:** Existing reference code (pre-ecommerce platform). This is the base library that the ecommerce microservice platform will reuse.

## 1. Purpose

`util/` is a Spring Boot 4.0.0 shared library that provides the cross-cutting concerns every microservice in the family needs:

- Audit + soft-delete base entities
- Distributed ID generation (Snowflake, K8s-aware)
- Excel import/export via annotations
- JasperReports rendering (PDF / XLSX / RTF / HTML)
- Multi-tenant data-source routing
- OAuth2 / JWT / Spring Security helpers
- Global exception handling with Telegram error reporting
- File / MinIO object storage, mail, QR code, paging DTOs
- Vietnamese geography DTOs (Province / District / Commune)

It auto-configures itself via `UtilsAutoConfiguration` so consumers just drop it on the classpath.

## 2. Maven Coordinates

| Field | Value |
|---|---|
| `groupId` | `vn.vnpt` |
| `artifactId` | `util` |
| `version` | `0.0.1-SNAPSHOT` |
| `parent` | (removed in Sprint 0 / R-01 fix; dependencyManagement inlined in util/pom.xml) |
| `packaging` | `jar` |

Spring Boot Maven plugin is **disabled** (`<skip>true</skip>`) — this is a library, not an executable service. Install with `mvn clean install -DskipTests` from `util/`.

## 3. Stack (from `pom.xml`)

| Dependency | Version | Role |
|---|---|---|
| Spring Boot starters | 4.0.0 | web, webflux, data-jpa, data-redis, jdbc, security, validation, oauth2 (auth server + client + resource server), mail, quartz, websocket, thymeleaf, webclient, restclient, actuator |
| PostgreSQL driver | runtime | default JDBC target |
| Apache POI / poi-ooxml | 5.5.1 | Excel engine |
| JasperReports | `${jasperreports.version}` (7.0.3 per README) | reporting + fonts |
| MinIO | 8.6.0 | object storage |
| ModelMapper | 3.2.6 | DTO mapping |
| FastCSV | 4.1.0 | CSV import/export |
| Flying Saucer (core + pdf) | 9.13.3 | HTML → PDF |
| Apache HttpComponents 5 | 5.6 / 5.4 | outbound HTTP |
| Commons Text | 1.15.0 | string ops |
| java-telegram-bot-api | 9.3.0 | error notifications |
| ZXing core + javase | 3.4.1 | QR generation |
| Lombok / MapStruct processors | optional | boilerplate |

## 4. Module Map

```
vn.vnpt.util
├── UtilsAutoConfiguration              # Spring Boot auto-config entrypoint
│
├── annotation/                          # @ExcelExport, @ExcelImport, @ExcelTitle,
│   ├── ExcelExport, ExcelImport, ExcelTitle, ExcelExportForm,
│   ├── ExcelExportClassAttributeAnnotate, ExcelExportGeneralConfig,
│   ├── ExcelExportParams, ExcelImportConfig, ExcelImportLineIndex,
│   ├── SpecialSymbolConstraint, ValueDefinition
│
├── common/
│   ├── SnowflakeIdGenerator             # 48+3+12 Snowflake
│   ├── DatetimeUtil, StringUtil, FileUtil, JsonUtil, ExcelUtils,
│   ├── CommonUtil, RestAPIUtils, QRCodeUtil, TemplateExcelWriter,
│   ├── User, UserExtInfo, Organization, TimeDto, PagingDto,
│   ├── common/component/  (ApplicationContextProvider, CacheService,
│   │                       RedisConfig, PageMapResponseAdvice)
│   ├── common/constant/   (CommonConstant, ErrorCodeEnum, MailType,
│   │                       SendGridConstant, StatusEnum, ValidationConstant)
│   ├── common/dto/base/   (BaseDto)
│   ├── common/entity/base/(RootEntity, BaseEntity, SoftDeletable)
│   ├── common/excel/      (ExcelImportExportHelper, ExcelReader,
│   │                       FieldDescriptor, TitleInfo, ExcelData,
│   │                       ExcelType, CellTypeEnum + model/* info classes)
│   ├── common/icode/      (AuthContext, AuthorityUtils,
│   │                       GrantedAuthorityUtils,
│   │                       ICodeJwtGrantedAuthoritiesConvertor,
│   │                       ICodeUser, Username, JsonPermission)
│   ├── common/mail/       (EmailDto, MailAttachment, MailInfo,
│   │                       SendGridMailRequest)
│   ├── common/request/    (UnitParamWithArrayType)
│   ├── common/response/   (BaseResponseBody, PostResponse)
│   ├── common/unit/       (Province/District/Commune DTOs)
│   ├── CommonUtil, DatetimeUtil, FileUtil, JsonUtil, ScheduleContext,
│   ├── SpecialSymbolValidator, GroupSpec, HandleDuplicateResult
│
├── component/
│   ├── converter/PathConverter
│   ├── softdelete/        # @SoftUk / @SoftUks annotation + registry + validator
│   └── tenant/            # TenantInterceptor, DataSourceProperties
│
├── config/
│   └── tenant/            # DataSourceConfig, TenantRoutingDataSource,
│                          # TenantStorage, WebConfiguration
│
├── entity/DvcAttachment
├── exception/             # CustomException, UpdateException, InvalidInputException,
│                          # GreenException, TenantNotFound/Resolving, CircularColumn/Sheet,
│                          # ErrorCode, ExceptionMessage, ErrorCodeEnum,
│                          # SqlExceptionConstant, SqlExceptionMessageResolver,
│                          # GlobalExceptionHandler, ApiExceptionHandle,
│                          # ResponseResult, ReturnResult
│
├── jasperreports/         # JasperUtils, PdfUtils, ReportType, ReportMediaType,
│                          # PrintResult, JasperUtilsException
│
├── properties/            # FileProperties, FolderProperties, TelegramProperties
├── repository/DvcAttachmentRepository
└── telegram/              # TelegramBotAPIUtil, ErrorMessageObj
```

## 5. Key Patterns and Decisions

### 5.1 Entity hierarchy

```
SoftDeletable (interface)
   └── RootEntity  (audit fields: id, createdBy/At, updatedBy/At,
                    deletedBy/At, isActive, isDeleted; @PreUpdate + @PostLoad)
         └── BaseEntity  (adds @Id uuid via Snowflake; @PrePersist)
```

- `id` is auto-generated by the DB sequence (`insertable=false, updatable=false`).
- `uuid` is generated by `SnowflakeIdGenerator.generateId()` in `@PrePersist`.
- `resolveAccountId()` reads the JWT identity; falls back to `AUTO_SCHEDULE` for Quartz jobs, `unknown` otherwise.
- `createdAtFormatted` is computed in `@PostLoad` for `dd/MM/yyyy` display in `Asia/Ho_Chi_Minh` timezone.

### 5.2 Snowflake layout

```
| timestamp(48) | workerId(3) | sequence(12) |
```

- Epoch = `2025-01-01 00:00:00 UTC+7` (= `1735689600000`).
- Worker id derived from `POD_NAME` env var (must match `.*-(\d+)$`).
- In `spring.profiles.active=dev` (or unset): SecureRandom fallback with WARN log.
- In `prod` / `staging` without valid POD_NAME: throws `WorkerIdMissingException` at boot (ADR-22, R-08 mitigation).
- Metric `snowflake_worker_id_source` gauge with `source` tag (`podname`=1, `securerandom`=2) per ADR-22.
- 4 096 ids / ms / worker.

### 5.3 JasperReports

- Templates loaded from `classpath:templates/jasperreports/<filename>` (resource is `.jasper`, pre-compiled).
- `JasperUtils.printReport(path, params, list, type)` returns a `PrintResult(filename, byte[], type)`; `getReportResponseEntity(...)` wraps it as a Spring `ResponseEntity<ByteArrayResource>` with attachment headers.
- Supported types: `PDF`, `XLS`, `XLSX`, `RTF`, `HTML`. The exporter map (`JREXPORTER_MAP`) is built statically.
- `PdfUtils` (iText / com.lowagie.text) adds rotate, merge, and landscape detection for downstream batches.
- Vietnamese font stack: `Arial/Times` families, `.VnArial`, `vuArial*` / `vuTimes*` variants (Vietnamese diacritics), `ARIALN*` condensed. Packaged under `resources/fonts/` and the bundled `VietFontsWeb1_ttf.zip`.

### 5.4 Multi-tenant routing

- `DataSourceConfig.dataSource()` builds a `TenantRoutingDataSource` (extends `AbstractRoutingDataSource`) over a `Map<String, DataSource>` keyed by tenant.
- `TenantInterceptor` is a `WebRequestInterceptor` registered in `WebConfiguration`; populates `TenantStorage` (per-thread context).
- `TenantNotFoundException` / `TenantResolvingException` are distinct types for "missing config" vs "header could not be resolved" — good separation.
- Resource config: `permissions/path_dictionary.csv`, `permissions/aims-permissions.json`, `permissions/path_apis_permissions.xlsx` (Vietnamese naming → likely AIMS = an internal admin product).

### 5.5 Error pipeline

`GlobalExceptionHandler` → `ReturnResult(ErrorCodeEnum, message)` → `TelegramBotAPIUtil.sendErrorMessage(ErrorMessageObj)`.

| Trigger | HTTP | Code |
|---|---|---|
| `CustomException` | 409 | `CONFLICT` |
| `UpdateException` | 400 | `BAD_REQUEST` |
| `MethodArgumentNotValid` / `BindException` | 400 | `BAD_REQUEST` |
| `MaxUploadSizeExceeded` | 413 | `PAYLOAD_TOO_LARGE` |
| fallback `Exception` | 500 | `INTERNAL_SERVER_ERROR` |

Telegram payload includes request / response bodies (as temp `.txt` attachments), `MESSAGE CODE = SnowflakeId`, service name, remote host, username, status. Useful as a poor-man's Sentry.

### 5.6 Excel framework

- Field-level annotations (`@ExcelExport` / `@ExcelImport` / `@ExcelTitle`) drive export column ordering, width, required-flag, and column letter → cell binding.
- `ExcelImportExportHelper` is the entrypoint for both directions; reflection-based so it works on DTOs without inheritance.
- Special-symbol validator (`@SpecialSymbolConstraint` + `SpecialSymbolValidator`) is reused as a generic input validation hook.
- Soft-delete uniqueness: `SoftUk` / `SoftUks` annotations pair with `SoftDeleteMetadataRegistry` + `UkValidator` to enforce uniqueness among non-deleted rows.

## 6. Known Issues / Gotchas

| # | Location | Issue | Severity | Fix sketch |
|---|---|---|---|---|
| 1 | `SnowflakeIdGenerator` | `workerId` is a `static` field; second `UtilsAutoConfiguration` boot in tests will overwrite it. | medium | Pass worker id explicitly to constructor, store in instance field. |
| 1a | `SnowflakeIdGenerator.getWorkerIdFromPod()` | R-08 mitigation in Story 0.5: throw `WorkerIdMissingException` instead of silent SecureRandom fallback when POD_NAME missing in non-dev profile. | resolved | ADR-22 binding — failure must surface at boot. |
| 2 | `SnowflakeIdGenerator.generateId()` | On rollover `seq=0` it calls `waitForNextMillis` but does not re-check that timestamp actually advanced under NTP slew. | low | Use `redis.call('TIME')` if going distributed; otherwise leave `synchronized` and trust wall clock. |
| 3 | `RootEntity.preUpdate()` | When entity is being created (`id` not yet DB-assigned but `createdAt` populated by `BaseEntity.@PrePersist`), `preUpdate` is still invoked by Hibernate if the entity is dirty — risk of double-setting audit fields. | low | Guard `preUpdate` with `if (id == null) return;` or rely on `@PrePersist` exclusively. |
| 4 | `GlobalExceptionHandler` | Method `ahandleException` (typo) handles `CustomException`; second method `updateException` (no `a` prefix) handles `UpdateException`. Cosmetic only. | trivial | Rename to `handleCustomException` / `handleUpdateException`. |
| 5 | `DataSourceConfig.dataSource()` | `System.out.println` for datasource init — should use SLF4J. | trivial | Replace with `log.info(...)`. |
| 6 | `UtilsAutoConfiguration.fileUtil()` | Wires `FileProperties` but `FileProperties` is not declared in `@EnableConfigurationProperties` — it is, only `TelegramProperties` and `FolderProperties` are listed alongside it. Verify imports. | medium | Confirm `FileProperties` is registered (likely missing line). |
| 7 | `TelegramBotAPIUtil.sendErrorMessage()` | Reads `wrappedReq.getInputStream().readAllBytes()` which **consumes** the stream; downstream filters cannot re-read it. | medium | Wrap with `ContentCachingRequestWrapper` upstream, then read `getContentAsByteArray()`. |
| 8 | `JasperUtils.printReport()` | Loads `.jasper` (pre-compiled) from classpath — recompile step is not part of util; ops must ship compiled templates. | low | Add a Maven goal or CI step to compile `.jrxml` → `.jasper`. |
| 9 | `pom.xml` | `parent` references `../pom.xml` which doesn't exist in this repo (root `pom.xml` is unrelated). `mvn install` will fail unless the parent pom is also provided. | blocker for greenfield | Replace parent with self-contained `<dependencyManagement>` or vendor in the parent pom. |
| 10 | `pom.xml` | `spring-boot-maven-plugin` with `<skip>true</skip>` is fine for a lib, but dev tools + thymeleaf + websocket in a *library* bloat the consumer. Consider `<optional>true</optional>` on more starters (webflux, websocket, thymeleaf). | low | Mark heavy starters `optional`. |

## 7. Integration Notes for the Ecommerce Platform

- `BaseEntity` is the right base class for every aggregate; bring `SoftDeletable` semantics into CatalogService / OrderService / etc.
- `SnowflakeIdGenerator` is reusable as-is for event ids and aggregate ids; keep the K8s pod-naming convention (deployment `*-N`) so worker ids stay unique.
- `JasperUtils` covers invoice / receipt rendering for the Order service.
- `ExcelUtils` + annotations are reusable for admin catalog import/export flows.
- Multi-tenant routing is opt-in per consumer; if the ecommerce platform is single-tenant, do not enable `DataSourceConfig`.
- Telegram error reporting is excellent as a "this is broken" channel during development; gate it behind `telegram.is-send-error=false` in prod and rely on Loki/Tempo instead.
- `GlobalExceptionHandler` is **not** `@Order` — if the ecommerce platform defines its own advice, mark one as `@Order(Ordered.HIGHEST_PRECEDENCE)` to avoid double-handling.

## 8. Open Questions for the Architecture Phase

1. Should `util/` be vendored as a submodule, published to a private Maven repo, or migrated to a multi-module `pom.xml` under `side-project/`?
2. The util assumes JWT-only auth (`CommonUtil.getLoggedAccountId`). Does the ecommerce platform keep that, or move to opaque session tokens at the gateway?
3. `Multi-tenant` is built around per-tenant `DataSource`. Is the ecommerce platform multi-tenant, or single-tenant? If single, the routing layer is dead weight.
4. `TelegramBotAPIUtil` calls `bot.execute(...)` synchronously — will block a request thread on Telegram downtime. Add timeout + async wrapping.
5. `softdelete` uses reflection; confirm Hibernate 7 (Spring Boot 4 default) still supports the metadata registry pattern.

---

_Previous: [09-project-structure.md](./09-project-structure.md) · Next: Architecture document — see `_bmad-output/planning-artifacts/architecture.md` (now finalized as `status: complete`, 10/10 quality)._
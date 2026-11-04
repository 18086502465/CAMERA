# CODEBUDDY.md

This file provides guidance to CodeBuddy Code when working with code in this repository.

## Project Overview

产线缺陷在线AI检测系统 — an industrial production-line defect detection platform. Cameras on the line report status heartbeats, detection counts, and defect images; the backend aggregates them into production-line statistics dashboards.

> **Full project documentation**: `docs/项目说明.md` — positioning, architecture, every external feature (CLI / APIs / config / business flows) with I/O, plus the development implementation plan (tech stack, module data flow, design trade-offs, build/test/deploy, extensibility, known limitations).
> Deployment: `docs/部署方案-Ubuntu22.04.md` · Operations: `docs/服务器运行与问题排查手册.md`

| Component | Stack | Location |
|---|---|---|
| Backend | Java 8, Spring Boot 2.7.15, MyBatis-Plus 3.5.3.1, MySQL 8.0, Druid, JJWT, Apache POI | `fact-product/` |
| Frontend | Vue 3.5, Vite 5, Element Plus 2.7, Pinia 3, ECharts 5, Axios | `frontend/` |
| Installer | Inno Setup 6, PowerShell, C# Windows Service wrapper | `installer/` |
| Release output | Built release dir + installer exe | `dist-release/` |
| Docs | Delivery docs, design specs, frozen API contract | `docs/` |

Fixed ports: backend `8091`, frontend dev `3000`, MySQL `3306`. DB name `wms-new`. The Maven artifact is still `time-bucket-demo` (legacy name, kept so the shipped jar filename stays `time-bucket-demo.jar`).

## Commands

### Backend (`fact-product/`)

```bash
# Full build (produces target/time-bucket-demo.jar)
mvn clean package

# Build without tests — the release pipeline uses this
mvn -q -DskipTests clean package

# Run all tests (8 tests across 3 controller contract test classes)
mvn test

# Run a single test class
mvn test -Dtest=BatchUploadControllerTest

# Run a single test method
mvn test -Dtest=BatchUploadControllerTest#batchUploadSuccess

# Run the app
mvn spring-boot:run
```

The first build downloads the full dependency tree (~7 min on a cold cache). Subsequent builds are fast. There is no Maven wrapper (`mvnw`) — a system Maven is required.

### Frontend (`frontend/`)

```bash
npm install
npm run dev       # vite dev server on :3000, proxies /api -> localhost:8091
npm run build     # production build into dist/
npm run preview   # serve the production build
```

There is **no lint, no format, and no test script** — `package.json` only defines `dev`/`build`/`preview`, and there is no ESLint/Prettier config in the project.

### Database

Schema is **not** auto-initialized. There is no `schema.sql`/`data.sql` and no `spring.sql.init` config, so the tables must be imported manually. See "Schema drift" below for the correct import order — this matters, because the three SQL scripts are not interchangeable.

### Release build

The one-shot build is `build-release.ps1` at repo root (**restored 2026-10-28** — it had been missing since June; only `dist-release/build-release-latest.log` survived, and that log is UTF-16). It runs 8 stages:

1. Build Vue dist → 2. Copy `frontend/dist` into `fact-product/src/main/resources/static` → 3. Build Spring Boot executable jar → 4. Create release directory layout → 5. Compile Windows service wrapper → 6. Copy local JRE into release package → 7. Build Inno Setup installer (if ISCC present) → 8. Report result

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-release.ps1
# -SkipFrontend / -SkipBackend  : reuse existing artifacts
# -NoInstaller                 : release dir only, skip the .exe
# -JreSource <path>            : explicit Java 8 JRE to embed
```

The script also **verifies that every file referenced by `installer/camera-detection-system.iss` really exists in the release dir** and aborts the build otherwise — an Inno Setup shortcut pointing at a missing file only surfaces after the user installs.

**PowerShell requirements**: scripts are UTF-8 **with BOM** (PS 5.1 reads BOM-less files as ANSI and chokes on Chinese text); `mysqldump`/`mysql` clients are located via PATH, `C:\mysql8\bin`, and the registry; Inno Setup 6 may be installed per-user at `%LOCALAPPDATA%\Programs\Inno Setup 6`.

A parallel `installer/linux/build-release.sh` produces the Linux tarball.

## Architecture

### Monorepo layout

```
fact-product/   Spring Boot backend (com.wms.*)
frontend/       Vue 3 SPA
installer/      Inno Setup script, PowerShell ops scripts, C# service wrapper
dist-release/   Build output (CameraDetectionSystem/ + installer/*.exe)
docs/           Delivery docs, design specs, frozen API contract
```

### Backend package layout

`com.wms` root, technical-layer grouped (`controller` / `service` / `mapper` / `entity` / `dto` / `vo` / `task` / `config` / `utils` / `repository` / `aspect`). Entity naming follows a `Dim*` (dimension), `Fact*` (fact/transaction), `Cfg*` (configuration), `Agg*` (aggregate), `Sys*` (system) prefix convention.

### Data access — three coexisting styles (important)

The codebase is mid-migration from ad-hoc SQL to a layered model. Know which one you're in:

| Style | Where | Pattern |
|---|---|---|
| MyBatis-Plus mappers | `mapper/` + `resources/mapper/*.xml` | `extends BaseMapper<T>`, `LambdaQueryWrapper`, entity-typed returns, logical delete via `isDeleted` |
| `JdbcTemplate` repositories | `repository/{analytics,stats,log}/` | Hand-written SQL strings, returns `Map<String,Object>`, **manual** `WHERE is_deleted=0`, manual `LIMIT ? OFFSET ?` pagination |
| Direct `JdbcTemplate` in controllers/services | 8 controllers + 3 services | Legacy, not yet migrated. `LineAnalyticsController` (1072 lines) and `StatsController` (886 lines) are the densest offenders |

`repository/` is the **target** layer. When adding complex/statistical queries, put them in a `repository/` class rather than in a controller.

**Map-key naming inconsistency inside `repository/`**: `LineAnalyticsRepository` aliases columns to camelCase (`product_id AS productId`), but `StatsQueryRepository` returns raw snake_case keys (`total_count`, `bucket_date`). Match the class you are reading/writing.

### Service layer

Three patterns coexist:
- `service/impl/*ServiceImpl` extending `ServiceImpl<Mapper, Entity>` — standard CRUD.
- `service/impl/inspection/InspectionUploadServiceImpl` and other large services — plain `implements` + `@Autowired JdbcTemplate`, no `ServiceImpl` base.
- `service/inspection/` holds the interface while its impl lives in `service/impl/inspection/` — intentional, but inconsistent with the other 17 interfaces which sit directly in `service/`.

### Response envelope

All APIs return `ResultVO<T>` (`com.wms.vo.ResultVO`): `{code, message, data}` where `code` is `200` success, `500` failure, `422` business-validation failure.

Two HTTP conventions coexist:
- Page/UI APIs: always HTTP 200, failure signalled by `code != 200` in the body.
- Device-facing `/api/v1/**` APIs: HTTP status and body `code` are synchronized (`ResponseEntity.status(422).body(ResultVO.fail(422, ...))`).

DTO/VO classes all `implements Serializable` with explicit `serialVersionUID`. Lombok `@Data` is used for most, but `dto/log/OperationLogQueryDTO` and `dto/ConfigImportResultDTO` use hand-written getters/setters — follow the file's local style.

### Authentication — enforced since 2026-10-28, but role checks are still missing

`config/SecurityConfig.java` + `utils/JwtUtil` + `utils/JwtRequestFilter` (Spring Security + JJWT, stateless sessions, `BCryptPasswordEncoder`).

**What was broken (fixed 2026-10-28)**: `JwtRequestFilter` parsed and validated the token but **never called `SecurityContextHolder.getContext().setAuthentication(...)`** — it only logged "JWT Token 验证成功". Combined with a ~60-pattern `permitAll()` whitelist that covered every business query path, the API was effectively open. Verified at runtime before the fix: `GET /api/cameras?page=1&size=1` returned `200` with real camera data **without any token**.

**Current state**:
- `JwtRequestFilter` writes authentication into the context, taking role/userId from the JWT (no extra DB round-trip)
- `SecurityConfig` whitelist reduced from ~60 to ~25 patterns: SPA static assets, **SPA page routes**, login/register, `/api/v1/**` (device), docs
- Anonymous access to business APIs now returns **403**; device endpoints (`/api/v1/**`, `cfg_camera_token`, service-layer check) are unaffected — the frozen contract is intact

**Still missing (P0, verified on the running server 2026-10-31)**: **no role-based authorization anywhere** — `@PreAuthorize` is used zero times and `SecurityConfig` has no `hasRole(...)`. The filter does populate `ROLE_ADMIN`/`ROLE_USER` authorities, but nothing consumes them. Result: a `role=0` worker token can call all 58 admin endpoints, including `DELETE /api/user/{id}` (verified — it deleted the admin account). Frontend hides the admin menu via `isAdmin`, but that is UI, not access control. **Before production, add `hasRole('ADMIN')` for the admin path prefixes and `@PreAuthorize` on the write methods.**

**Also fixed 2026-10-31**: `AuthController.register()` used to trust `request.getRole()` — since `/api/auth/register` is on the whitelist, anyone could register an admin account anonymously (verified end-to-end). The role is now always forced to `0` server-side. Consider removing `/api/auth/register` from the whitelist entirely for production.

So: adding a path to `permitAll()` is **not** required for the frontend to work — the JWT filter now really does enforce it. And do not assume any endpoint is role-protected.

### Operation logging

`aspect/OperationLogAspect.java` — an `@Around` advice on `execution(* com.wms.controller..*(..))`. No annotation required; it intercepts every public controller method automatically and writes to `sys_operation_log`. It derives `operationType` from the method-name prefix (`login`→LOGIN, `save`/`create`/`add`→CREATE, `update`/`edit`→UPDATE, `delete`/`remove`→DELETE, `export`→EXPORT, else QUERY) and `moduleName` from the controller class name. It skips URIs starting with `/api/logs/operation` to avoid self-recording, and swallows its own save failures so logging never breaks business logic.

### Scheduled work

`task/DataCleanupTask.java` — `@Scheduled(cron = "0 0 2 * * ?")`: deletes images older than 30 days, archives + deletes `fact_production_30min` older than 3 months, deletes operation logs older than 6 months.

`task/InitTimeBucketRunner.java` — `CommandLineRunner`; on empty `dim_time_bucket_30min` inserts 48 half-hour buckets/day from today−7 days to today+1 year (~19k single inserts).

`LineAnalyticsController` also contains a `@Scheduled(fixedDelay = 60000)` daily-aggregation poller — a `@Scheduled` method living in a controller is an architectural smell here, but it is intentional for now.

### Runtime DDL — schema is not only in .sql files

Several components create/alter tables at startup via `@PostConstruct`:

- `LineAnalyticsController.ensureDailyAggregationTables()` / `ensureAggregationScheduleTable()` / `ensureAggregationJobTable()` — `CREATE TABLE IF NOT EXISTS` for `agg_quality_day`, `agg_defect_type_day`, `analytics_aggregation_schedule`, `analytics_aggregation_job`.
- `SystemSettingsController.init()` — ensures + seeds `sys_system_setting` and `sys_data_retention_policy`.
- `CameraStatusServiceImpl.initSchema()` — `ALTER TABLE camera_status ADD COLUMN ...` for 7 columns, guarded by `information_schema.COLUMNS` checks.
- `DefectImageServiceImpl` — similar self-healing.

When debugging "table missing" or "column missing" errors, check the SQL scripts **and** this runtime DDL.

### Frontend architecture

- **Routing**: `src/router/index.js`, all views lazy-loaded via `() => import(...)`. Vue history mode — the backend serves a SPA fallback (`SpaFallbackController` forwards non-asset, non-API paths to `index.html`). Verified: `GET /system/config` returns `200` with the SPA shell.
- **Auth**: `utils/auth.js` wraps three `localStorage` keys (`camera_token`, `camera_user`, `camera_token_expire`). `stores/user.js` is the Pinia store. The router guard for `meta.requiresAdmin` reads `localStorage` directly rather than using the store.
- **HTTP**: `utils/request.js` creates an axios instance with `baseURL: '/api'` and 15s timeout. The request interceptor attaches `Authorization: Bearer <token>` only if the token is unexpired. **The response interceptor does not unwrap** — business code must read `res.data.data` itself. The 401 handler clears the token and does `window.location.href = '/login'`.
- **API modules**: `src/api/*.js` export bare functions that call `request(...)` or `request.get/post/...`. Because `baseURL` already contains `/api`, **API module URLs must NOT include the `/api` prefix** (e.g. `'/auth/login'`, not `'/api/auth/login'`) — the backend controllers already map `/api/...`.
- **Global filter cascade**: `components/Layout.vue` provides `filterParams` / `filterLabels` via `provide`; seven views inject them (`Dashboard`, `CameraStatusView`, `CameraImageRecords`, `DefectsView`, `YieldView`, `GalleryView`, `LineAnalyticsView`). Views watch `filterParams` with `{deep: true}` to refetch.
- **View + composable pattern**: `views/camera/useCameraStatusTable.js` and `views/system/config/useConfigRiskConfirm.js` hold pure logic (filters, sorting, pagination, risk confirmation) outside the `.vue` file. `LineAnalyticsView.vue` does *not* follow this and keeps everything inline — a known inconsistency.
- **Shared components**: `components/common/Base*.vue` — `BaseState` is the base, with `BaseEmptyState`/`BaseLoadingState`/`BaseErrorState` as thin `type`-fixed wrappers, plus `BasePagination`. Adoption is partial: `BaseEmptyState`/`BaseLoadingState`/`BaseErrorState` have zero consumers (the actual component in use is `components/EmptyState.vue`, which wraps `BaseState`), and `BasePagination` is used in only 3 of ~20 places.
- **ECharts**: instances are tracked in a Map and `dispose()`d in `onBeforeUnmount`.

### Frozen device API contract

`docs/api/接口冻结契约-v1.0-20260616.md` freezes three camera-facing endpoints — **do not change URLs, header names, field names, or error codes without a protocol version bump and compatibility window**:

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/v1/camera/status/heartbeat` | POST | Heartbeat, run state, resource metrics |
| `/api/v1/camera/inspection/batch-upload` | POST | Incremental detection count report |
| `/api/v1/defect-images/upload` | POST multipart/form-data | Defect images + metadata |

Required headers on all three: `Authorization: Bearer <token>`, `X-Device-Id`; `X-Batch-Id` for batch upload (idempotency key).

**The three contract tests in `src/test/java/com/wms/controller/` must pass before any change to a controller, DTO, response shape, or SQL migration.** They are the regression net for the refactor effort described in `docs/refactor/`.

## Testing conventions

Backend tests are **JUnit 5 + Mockito standalone MockMvc** — deliberately *no* `@SpringBootTest`, no Spring context, no database, no Testcontainers, no H2:

```java
@ExtendWith(MockitoExtension.class)
class XxxControllerTest {
    @Mock private IService service;
    @InjectMocks private XxxController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
}
```

Conventions to preserve:
- Every test has an English `@DisplayName`.
- Private `@Value`-injected fields cannot be populated by standalone setup — inject them with `ReflectionTestUtils.setField(controller, "maxBatchImages", 100)`.
- Negative cases assert both the HTTP status *and* the body `code` (`$.code`), and use `verify(service, never())` to prove the service was not reached.

Coverage is limited to the 3 controller contract test classes — there are no service, repository, task, or utility tests. `JwtUtil`, `ShiftCalculator`, and `ExcelUtils` are pure functions and are the natural next candidates.

The frontend has no test infrastructure at all.

## Release and deployment

```
dist-release/CameraDetectionSystem/
├─ app/time-bucket-demo.jar    # Spring Boot fat jar with Vue static assets embedded
├─ config/application.yml      # external config (overrides jar-internal one)
├─ service/CameraDetectionService.exe
├─ jre/                        # bundled JRE 8 — target machines need no Java
├─ scripts/*.ps1               # ops scripts
├─ sql/wms-new.sql
├─ data/uploads/{images,defect-images}
└─ logs/
```

`installer/camera-detection-system.iss` (Inno Setup) bundles that directory into `dist-release/installer/CameraDetectionSystem-Setup-1.0.0.exe` (~101 MB), installs to `C:\Program Files\CameraDetectionSystem`, and asks for a working directory that is written to `config/workdir.txt`.

**The Windows service is a C# wrapper, not the JVM itself.** `installer/service-wrapper/CameraDetectionService.cs` (`CameraDetectionService : ServiceBase`) launches `java.exe -Dapp.home=<workdir> -jar time-bucket-demo.jar --spring.config.additional-location=file:<config>` as a child process and supervises it. Java resolution order: bundled `jre/bin/java.exe` → `%JAVA_HOME%\bin` → `%JAVA_HOME%\jre\bin` → `PATH`. Service name `CameraDetectionSystem`, display name `Camera Detection System`, auto-start, restart 3× at 60s intervals.

`app.home` is the anchor for all writable paths: `data/uploads/images`, `data/uploads/defect-images`, `logs/camera-detection-system.log`. It defaults to `./runtime` in dev and is set explicitly by the service wrapper via `-Dapp.home`.

`installer/scripts/verify-installation.ps1` runs 9 checks (JRE, jar, config, SQL, service exists, service running, port 8091 listening, page 200, and a >100-image upload expecting HTTP 413) and exits 1 on any failure.

`installer/scripts/*.ps1` and `installer/templates/application.yml` are byte-identical to their copies in `dist-release/CameraDetectionSystem/`. **Change both, or change one and copy it back.**

The production template (`installer/templates/application.yml`) differs from the dev `application.yml`: smaller Druid pool (5/5/100 vs 10/10/200), no `stat,wall` filters, no MyBatis SQL stdout logging, `root: INFO` instead of `com.wms.mapper: DEBUG`, and `app.home` defaulting to `./`.

## Conventions to follow

**Backend** (from `docs/交付件-20260602/05-后端设计规范.md`):
- Controllers do parameter validation, call a service, and return `ResultVO` — no complex SQL or cross-table business logic.
- New endpoints use `/api/v1/...` or `/api/{resource}`.
- Device endpoints must validate `Authorization: Bearer <token>` and `X-Device-Id`; uploads must limit file type, size, and count.
- Multi-table writes use `@Transactional(rollbackFor = Exception.class)`.
- SQL must use parameter binding — never concatenate user input. Always cap pagination `size`.
- Errors return a friendly `message`; stack traces go to the log, never to the client.

**Frontend** (from `frontend/design.md`, the authoritative design spec):
- Industrial monitoring look: white cards, blue primary (`#1677FF`), information-dense, strong status-color separation.
- 4px minimum spacing unit with an 8px rhythm.
- Status color mapping is fixed: running/normal/OK → green; standby/delay/WARNING → amber; fault/defect/CRITICAL → red; stopped/offline/none → grey.
- Pagination layout `total, sizes, prev, pager, next`, placed bottom-right of the table card; default page size 10, options 10/20/50/100; page-size change resets to page 1.
- Content area max-width 1440px; top bar sticky; global factory/line/station filter at top center.
- Tables need loading, empty, and pagination states; status must be conveyed by color *and* text; failed images need a reason and a remediation hint.
- `design.md` §3.5 notes three competing color palettes coexist (Tailwind `#2563eb`, Element Plus `#409eff`, Ant Design `#1677FF`) — do not introduce new scattered color values before converging them.

## Known issues and caveats

Read these before assuming a bug in your own code.

### Schema drift — the most important one

There are **three divergent schema generations**, and none of them alone gives you a working system. Verified against the live `wms-new` database:

| Script | Tables | Notes |
|---|---|---|
| `resources/sql/init_schema.sql` | 17 | Oldest baseline. Uses `sys_user.password`, but the entity maps `passwordHash` → `password_hash`, so **BCrypt login cannot work against a DB built from this script alone**. |
| `resources/sql/final_schema_p0_20260608.sql` | 21 | The current target. `DROP TABLE` + `CREATE TABLE` for 21 tables including `dim_camera`, `cfg_camera_token`, `fact_inspection_batch`, `agg_quality_30m`. |
| `resources/sql/system_settings_20260611.sql` | 2 | `sys_system_setting`, `sys_data_retention_policy`. |
| `dist-release/.../sql/wms-new.sql` | 25 | The **delivered installer** script — a Navicat export dated 2026-05-31, i.e. *older than* the P0 schema. |

Consequences:
- The **delivered installer SQL is missing all 15 P0 tables** (`dim_camera`, `cfg_camera_token`, `cfg_camera_defect_mapping`, `fact_inspection_batch`, `fact_inspection_defect_count`, `fact_inspection_mapping_exception`, `fact_camera_heartbeat`, `fact_camera_status_current`, `dim_product`, `dim_defect_position`, `agg_quality_30m`, `agg_defect_type_30m`, `cfg_analytics_dashboard`, `cfg_analytics_widget`, `sys_data_retention_policy`). Installing from the shipped `wms-new.sql` therefore produces a database in which the frozen batch-upload API fails.
- The correct import order for a fresh dev database is **`init_schema.sql` → `final_schema_p0_20260608.sql` → `system_settings_20260611.sql`** (union = 34 tables). `final_schema_p0` drops and recreates the tables it owns, so it must run *after* `init_schema`, not before.
- Confirmed at runtime against the live DB (23 tables, P0 not applied): `POST /api/v1/camera/inspection/batch-upload` returns `code:500` with `Table 'wms-new.dim_camera' doesn't exist`, while `/api/v1/camera/status/heartbeat` returns `code:200` (it writes to `camera_status`, which does exist). **If the batch-upload endpoint 500s with a missing-table error, apply the P0 script — the code is not at fault.**
- The live `sys_user` table has `password_hash` and holds one seeded admin (`admin`, `role=1`, `$2b$12$` hash). `init_schema.sql` still says `password` — a leftover that no longer matches reality.

### MyBatis-Plus duplicate mapper definitions

MyBatis-Plus resolves duplicate statement IDs with priority **XML > `@Select` annotation > injected CRUD SQL** (see `MybatisConfiguration.addMappedStatement`). Two mappers are defined in *both* an annotation and an XML file, and MyBatis-Plus logs an ERROR at startup for each:

- `FactProduction30minMapper.countByTimeBucketAndStation` — the XML version wins, and it queries `time_bucket_id` / `station_id` with params `timeBucketId` / `stationId`, while the Java method declares `@Param("bucketDate")` / `bucketIndex` / `workstationId`. The XML also references a `time_bucket_id` column that **does not exist in any schema script** (verified against the live DB). The method has no callers, so this is dormant — but it will fail if anyone calls it.
- `SysHolidayConfigMapper.selectByDate` — benign; both definitions are equivalent `SELECT * FROM sys_holiday_config WHERE config_date = #{configDate}`.

Do not add new annotations to methods that already have an XML definition — the annotation will be silently ignored.

### Other caveats

- **`src/styles/global.css` is never imported.** Its CSS variables and Element Plus theme overrides do not apply. The only effective global styles are four lines in `App.vue`. `design.md` §14.2 lists this as an open item — either import it or don't rely on its tokens.
- **`schemaVersion` mismatch**: `InspectionUploadServiceImpl.validateBasic()` requires exactly `"2.0"`, but the DTO Javadoc and the contract test both use `"1.0"`. The test passes only because the service is mocked. Confirmed at runtime: `"1.0"` is rejected, `"2.0"` is accepted.
- **Logical delete is inconsistent**: MyBatis-Plus global config points at the `isDeleted` property, but every schema script stores `is_deleted`. Services hand-roll `setIsDeleted(1)` to work around it.
- **`@Scheduled` inside `LineAnalyticsController`** — an intentional-but-smelly exception to the `task/` package convention.
- **`task/InitData.java` is an empty class** — dead code, and there is no seeded admin user in `init_schema.sql` beyond the raw INSERT. Insert users manually if needed.
- **Stale build artifacts**: `fact-product/src/main/resources/static/assets` (45 files) and `frontend/dist/assets` (45 files) are two separate copies of the same Vite output, and the release pipeline copies one over the other. The shipped jar contains **73** asset files — 28 stale leftovers from earlier builds, since `mvn package` does not clear `target/classes/static`. Harmless (only 2 are referenced by `index.html`; the rest are lazy-loaded route chunks), but don't be surprised by the count.
- **Credentials are hardcoded** in `application.yml` (DB password `<数据库口令>`, JWT secret) and in `installer/scripts/init-database.ps1` defaults. `utils/CodeGenerator.java` contains a *different* DB password than `application.yml`.
- **No root-level git repository.** `fact-product/src/.git` is an empty initialized repo with no commits; `fact-product/` itself is not tracked from this workspace. Do not assume `git` operations will work at the repo root.
- **`build-release.ps1` is missing** from the repo root despite being referenced throughout `docs/`. See the Release build section above for the stages it performed.
- The Maven `artifactId` is `time-bucket-demo` and the Java root package is `com.wms`, both legacy names for this product. `docs/代码结构梳理与优化建议-20260616.md` recommends renaming after release stabilization.

---
audience: new-dev-joining-sprint-0
project: side-project
date: 2026-07-06
sprint: 0 (Foundations)
how-to-use: Day 1 onboarding for any dev joining Sprint 0. Read sequentially; each step has command + verification.
---

# Sprint 0 Onboarding — Day 1

> **Sprint 0 goal:** Fix the `util/` parent pom blocker (R-01), bootstrap the multi-module Maven monorepo, get the dev platform running, set up CI, and ship Snowflake strict mode (R-22).
> **Without Sprint 0 working, no other sprint can start.** You are the foundation.

---

## 0. Pre-day-1 checklist (your laptop)

| Tool | Version | Verify |
|---|---|---|
| Java | 25 (LTS) | `java --version` |
| Maven | 3.9+ | `mvn --version` |
| Docker | 24+ | `docker --version` |
| Docker Compose | v2 | `docker compose version` |
| Git | 2.40+ | `git --version` |
| IDE | IntelliJ IDEA / VS Code | With Java + TypeScript plugins |
| kafkacat | latest | `kafkacat -V` (optional, for debugging) |
| psql | 16 | `psql --version` |

If you don't have these, ask your lead — there's a `dev-bootstrap.sh` script (per platform) that installs everything.

---

## 1. Get the code (15 min)

```bash
# 1. Clone (replace with your fork URL)
git clone https://github.com/your-org/side-project.git
cd side-project

# 2. Read the onboarding first
cat _bmad-output/AGENT-ONBOARDING.md
# (10 min read — required)

# 3. Read the Sprint 0 stories
sed -n '/^## Epic 0:/,/^## Epic 1:/p' _bmad-output/planning-artifacts/epics.md
# (5 min — see what's coming)
```

> **Question?** Open a GitHub issue or DM your lead. Don't guess.

---

## 2. Fix util/ parent pom (R-01) — Story 0.1 (30 min)

> **Why this is the FIRST story:** Nothing else builds without it. R-01 is the only true blocker for the entire project.

### Current state

`util/pom.xml` has:
```xml
<parent>
    <groupId>vn.vnpt</groupId>
    <artifactId>be</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <relativePath>../pom.xml</relativePath>  <!-- this file doesn't exist -->
</parent>
```

This breaks `mvn install` for everyone.

### Two fix options (per architecture §"Detail: ADR-01" discussion)

#### Option A: Inline `<dependencyManagement>` (RECOMMENDED for v1)

```xml
<!-- util/pom.xml — replace <parent> with <dependencyManagement> -->
<project ...>
    <modelVersion>4.0.0</modelVersion>
    <groupId>vn.vnpt</groupId>
    <artifactId>util</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <java.version>25</java.version>
        <maven.compiler.source>25</maven.compiler.source>
        <maven.compiler.target>25</maven.compiler.target>
        <spring-boot.version>4.0.0</spring-boot.version>
        <spring-cloud.version>2025.1.0</spring-cloud.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- existing util dependencies -->
    </dependencies>

    <build>
        <plugins>
            <!-- Spring Boot Maven plugin disabled (per local-docs/10) -->
        </plugins>
    </build>
</project>
```

**Why A is recommended:** Faster, doesn't require a new parent pom module.

#### Option B: Vendor parent pom at project root

```bash
# 1. Create /pom.xml with vn.vnpt:be:0.0.1-SNAPSHOT content
# (copy from old vnpt-be monorepo if you have access)

# 2. Add <modules><module>util</module></modules> to it
# 3. util/pom.xml stays unchanged
```

**Why B is longer-term right:** If you plan a true multi-module monorepo, vendor the parent.

### Verify

```bash
# From project root
mvn -pl util -am clean install -DskipTests
# Expected: BUILD SUCCESS

# Build Spring Boot autoconfig
mvn -pl util spring-boot:run
# Expected: Tomcat starts on :8080, "Started UtilApplication in X seconds"
```

### Commit

```bash
git checkout -b fix/r-01-util-parent-pom
git add util/pom.xml
git commit -m "fix(util): replace parent with inline dependencyManagement (R-01)

Resolves the vn.vnpt:be parent pom blocker that prevented the
entire monorepo from building. Uses Spring Boot 4.0.0 + Spring
Cloud 2025.1.0 BOM as per architecture version matrix.

Refs: architecture §'Detail: ADR-01', RISK-REGISTER R-01, Story 0.1"

git push -u origin fix/r-01-util-parent-pom
# Open PR; get 1 reviewer
```

---

## 3. Bootstrap monorepo (R-XX mitigation) — Story 0.2 (60 min)

Per architecture §"Project Structure & Boundaries":

### Create the directory tree

```bash
# From project root
mkdir -p services/{catalog,inventory,cart,checkout,payment,order,fulfillment,returns,customer,search,notification,admin,pricing,invoice}
mkdir -p bff/{storefront-bff,admin-bff}
mkdir -p frontend/{storefront,admin,packages/{ui,types,eslint-config}}
mkdir -p platform/{observability/{grafana-dashboards,prometheus-rules,loki-schemas,tempo-config},policies/opa,chaos/chaos-mesh,runbooks,ci-cd/{.github/workflows,argocd}}
mkdir -p helm/{catalog,inventory,cart,checkout,payment,order,fulfillment,returns,customer,search,notification,admin,pricing,invoice,storefront-bff,admin-bff,umbrella}
mkdir -p dev/{docker-compose,seed-data,chaos,localstack,scripts}
mkdir -p docs/{adr,tutorials,diagrams}
```

### Create root `pom.xml` (parent)

```xml
<!-- pom.xml — root parent for all modules -->
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>vn.vnpt</groupId>
    <artifactId>side-project-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>util</module>
        <module>services/catalog</module>
        <module>services/inventory</module>
        <!-- ... etc per architecture §'Project Structure' -->
    </modules>

    <properties>
        <java.version>25</java.version>
        <maven.compiler.source>25</maven.compiler.source>
        <maven.compiler.target>25</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.13.0</version>
                    <configuration>
                        <release>25</release>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

### Verify

```bash
mvn validate
# Expected: BUILD SUCCESS
```

---

## 4. Bring up the dev platform — Story 0.3 (45 min)

```bash
cd dev
# Create dev/docker-compose.yml (per architecture §'Project Structure')

docker compose up -d
# Wait for all 8 services to be healthy (~60 seconds)
docker compose ps
# Expected: 8 containers with "healthy" status

# Verify each
curl -sf http://localhost:8080/apis/registry/v2/groups  # Apicurio
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 --list  # Kafka
psql -h localhost -U catalog -d catalog -c "SELECT 1;"  # Postgres
redis-cli ping  # Redis
curl -sf http://localhost:9200  # Elasticsearch
```

### Troubleshoot (if anything fails)

```bash
# Check logs
docker compose logs kafka --tail=50

# Verify network
docker network ls | grep side-project

# Reset
docker compose down -v  # nuclear: delete volumes
docker compose up -d
```

---

## 5. CI scaffold — Story 0.4 (90 min)

Create `.github/workflows/pr.yml`:

```yaml
name: PR
on: [pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '25', distribution: 'temurin' }

      - name: Build
        run: mvn -B clean install -DskipTests

      - name: Unit tests
        run: mvn -B test

      - name: Integration tests
        run: mvn -B verify -P integration-tests
        env: { TESTCONTAINERS_RYUK_DISABLED: 'true' }

      - name: Archunit
        run: mvn -B test -Dtest='*ArchUnit*'

      - name: Avro compat check
        run: ./scripts/check-avro-compat.sh

      - name: Lint (Spotless + Prettier)
        run: mvn -B spotless:check && (cd frontend && npm run lint)

      - name: Build Docker images
        run: ./scripts/build-images.sh
```

Add the scripts to `scripts/` (use `chatgpt` or your lead to bootstrap them).

### Verify locally

```bash
# Install act (https://github.com/nektos/act) to test locally
act -j build
```

---

## 6. Snowflake strict mode (R-22) — Story 0.5 (45 min)

Edit `util/src/main/java/vn/vnpt/util/common/SnowflakeIdGenerator.java`:

```java
public static long getWorkerIdFromPod() {
    String podName = System.getenv("POD_NAME");
    if (podName == null || !podName.matches(".*-(\\d+)$")) {
        // Check profile
        String profile = System.getProperty("spring.profiles.active", "default");
        if (!profile.equals("dev") && !profile.contains("dev")) {
            // Production: throw (R-22 mitigation)
            throw new WorkerIdMissingException(
                "POD_NAME env var must be set in non-dev profiles to prevent worker-id collisions. " +
                "Expected format: <deployment-name>-<worker-id>, e.g., 'catalog-service-0'."
            );
        }
        // Dev: fall back with WARN log
        log.warn("POD_NAME missing in dev profile; using SecureRandom fallback. " +
                 "This is acceptable for dev only; production deploys must set POD_NAME.");
        return new SecureRandom().nextInt(8);
    }
    return Long.parseLong(podName.replaceAll(".*-(\\d+)$", "$1")) % 8;
}
```

Add a test:

```java
@Test
void getWorkerIdFromPodThrowsInProd() {
    System.setProperty("spring.profiles.active", "prod");
    System.clearProperty("POD_NAME");
    assertThrows(WorkerIdMissingException.class,
        () -> SnowflakeIdGenerator.getWorkerIdFromPod());
}

@Test
void getWorkerIdFromPodSucceedsInDev() {
    System.setProperty("spring.profiles.active", "dev");
    assertDoesNotThrow(() -> {
        long id = SnowflakeIdGenerator.getWorkerIdFromPod();
        assertThat(id).isBetween(0L, 7L);
    });
}
```

### Verify

```bash
mvn -pl util test
# Expected: WorkerIdMissingException for prod profile; success for dev
```

---

## 7. End of Sprint 0 checklist

- [ ] `mvn -pl util -am clean install` exits 0
- [ ] `mvn validate` at root exits 0
- [ ] `docker compose -f dev/docker-compose.yml up -d` brings 8 healthy services
- [ ] `.github/workflows/pr.yml` is in repo
- [ ] SnowflakeIdGenerator throws in non-dev profile
- [ ] All Sprint 0 stories (0.1..0.5) marked `done` in `sprint-status.yaml`

**Then** announce in Slack: "Sprint 0 complete; Sprint 1 starting."

---

## 8. Day 2-5: continue to Sprint 1

Read `SPRINT-1-DEV-HANDBOOK.md` and `EPIC-1-STORIES-QUICKREF.md` to start Sprint 1 (Catalog + Inventory).

// 08-infrastructure-and-deployment.md
# 8. Infrastructure & Deployment

Docker Compose is used for local development, providing the complete infrastructure backbone. Production targets Kubernetes via GitOps (ArgoCD).

## Docker Compose Setup (Local Dev)

```yaml
name: ecommerce-platform

networks:
  platform-net:
    driver: bridge

volumes:
  pg-product: {}
  pg-customer: {}
  pg-order: {}
  pg-payment: {}
  pg-inventory: {}
  es-data: {}
  redis-data: {}
  kafka-data: {}
  minio-data: {}

services:

  # ---- Datastores (one per service) ----
  pg-product:
    image: postgres:17
    environment:
      POSTGRES_DB: product
      POSTGRES_USER: product
      POSTGRES_PASSWORD: product
    command: ["postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=10"]
    ports: ["5432:5432"]
    networks: [platform-net]
    volumes: [pg-product:/var/lib/postgresql/data]

  pg-customer:
    image: postgres:17
    command: ["postgres", "-c", "wal_level=logical"]
    environment: { POSTGRES_DB: customer, POSTGRES_USER: customer, POSTGRES_PASSWORD: customer }
    ports: ["5433:5432"]
    networks: [platform-net]
    volumes: [pg-customer:/var/lib/postgresql/data]

  pg-inventory: { image: postgres:17, command: ["postgres","-c","wal_level=logical"], ports: ["5434:5432"], networks: [platform-net], volumes: [pg-inventory:/var/lib/postgresql/data] }
  pg-order:     { image: postgres:17, ports: ["5435:5432"], networks: [platform-net], volumes: [pg-order:/var/lib/postgresql/data] }
  pg-payment:   { image: postgres:17, ports: ["5436:5432"], networks: [platform-net], volumes: [pg-payment:/var/lib/postgresql/data] }

  # ---- Kafka (KRaft, no ZK) ----
  kafka:
    image: confluentinc/cp-kafka:7.7.0
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_LOG_RETENTION_HOURS: 168
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
      CLUSTER_ID: ecommerce-platform-cluster
    ports: ["9092:9092"]
    networks: [platform-net]
    volumes: [kafka-data:/var/lib/kafka/data]

  kafka-init:
    image: confluentinc/cp-kafka:7.7.0
    depends_on: [kafka]
    networks: [platform-net]
    entrypoint: ["/bin/sh","-c"]
    command:
      - |
        kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic cdc.products.product --partitions 6 --config cleanup.policy=compact
        kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic cdc.inventory.stock-item --partitions 8 --config cleanup.policy=compact
        kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic order.lifecycle --partitions 12 --config retention.ms=7776000000
        kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic order.saga.commands --partitions 12
        kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic order.saga.replies --partitions 12
        kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic payment.events --partitions 8
        kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic rating.events --partitions 6
        kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic recommendation.events --partitions 8
        kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic promotion.events --partitions 4 --config cleanup.policy=compact
        kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic cart.events --partitions 6
        kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic notification.events --partitions 8
        kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic delivery.events --partitions 6
        kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic audit.events --partitions 6

  # ---- Schema Registry (Apicurio) ----
  schema-registry:
    image: apicurio/apicurio-registry-mem:2.6.x
    environment:
      REGISTRY_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      REGISTRY_AUTH_ANONYMOUS_READ_ACCESS_ENABLED: "true"
    ports: ["8081:8080"]
    depends_on: [kafka]
    networks: [platform-net]

  # ---- Debezium Connect ----
  debezium:
    image: quay.io/debezium/connect:2.7
    environment:
      BOOTSTRAP_SERVERS: kafka:9092
      GROUP_ID: debezium-cluster
      CONFIG_STORAGE_TOPIC: debezium_configs
      OFFSET_STORAGE_TOPIC: debezium_offsets
      STATUS_STORAGE_TOPIC: debezium_statuses
      KEY_CONVERTER: io.apicurio.registry.utils.converter.AvroConverter
      VALUE_CONVERTER: io.apicurio.registry.utils.converter.AvroConverter
      CONNECT_KEY_CONVERTER_APICURIO_REGISTRY_URL: http://schema-registry:8080/apis/registry/v2
      CONNECT_VALUE_CONVERTER_APICURIO_REGISTRY_URL: http://schema-registry:8080/apis/registry/v2
    ports: ["8083:8083"]
    depends_on: [kafka, schema-registry]
    networks: [platform-net]

  # ---- Redis (rate limiter state) ----
  redis:
    image: redis:7.4-alpine
    command: ["redis-server","--maxmemory","512mb","--maxmemory-policy","allkeys-lru","--appendonly","yes"]
    ports: ["6379:6379"]
    networks: [platform-net]
    volumes: [redis-data:/data]

  # ---- Elasticsearch ----
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.15.0
    environment:
      discovery.type: single-node
      xpack.security.enabled: "false"
      ES_JAVA_OPTS: "-Xms1g -Xmx1g"
    ports: ["9200:9200"]
    networks: [platform-net]
    volumes: [es-data:/usr/share/elasticsearch/data]

  # ---- Object storage (product media) ----
  minio:
    image: minio/minio:latest
    command: ["server","/data","--console-address",":9001"]
    environment: { MINIO_ROOT_USER: minio, MINIO_ROOT_PASSWORD: minio123 }
    ports: ["9000:9000","9001:9001"]
    networks: [platform-net]
    volumes: [minio-data:/data]

  # ---- Observability ----
  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.110.0
    command: ["--config=/etc/otel/config.yaml"]
    volumes: ["./otel/config.yaml:/etc/otel/config.yaml:ro"]
    ports: ["4317:4317","4318:4318"]
    networks: [platform-net]

  prometheus:
    image: prom/prometheus:v2.54.1
    volumes: ["./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro"]
    ports: ["9090:9090"]
    networks: [platform-net]

  tempo:
    image: grafana/tempo:2.6.0
    command: ["-config.file=/etc/tempo.yaml"]
    volumes: ["./tempo/tempo.yaml:/etc/tempo.yaml:ro"]
    ports: ["3200:3200","14268:14268"]
    networks: [platform-net]

  loki:
    image: grafana/loki:3.2.0
    command: ["-config.file=/etc/loki/local-config.yaml"]
    ports: ["3100:3100"]
    networks: [platform-net]

  grafana:
    image: grafana/grafana:11.3.0
    environment: { GF_AUTH_ANONYMOUS_ENABLED: "true", GF_AUTH_ANONYMOUS_ORG_ROLE: Admin }
    ports: ["3000:3000"]
    depends_on: [prometheus, tempo, loki]
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning
      - ./grafana/dashboards:/var/lib/grafana/dashboards
    networks: [platform-net]

  # ---- Services (example for two; rest follow same pattern) ----
  api-gateway:
    build: { context: ./services/api-gateway }
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SPRING_DATA_REDIS_HOST: redis
      OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317
    ports: ["8080:8080"]
    depends_on: [redis, customer-service, product-service]
    networks: [platform-net]

  customer-service:
    build: { context: ./services/customer-service }
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SPRING_DATASOURCE_URL: jdbc:postgresql://pg-customer:5432/customer
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      APICURIO_REGISTRY_URL: http://schema-registry:8080/apis/registry/v2
      OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317
    depends_on: [pg-customer, kafka, schema-registry]
    networks: [platform-net]

  product-service:
    build: { context: ./services/product-service }
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SPRING_DATASOURCE_URL: jdbc:postgresql://pg-product:5432/product
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      APICURIO_REGISTRY_URL: http://schema-registry:8080/apis/registry/v2
      OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317
    depends_on: [pg-product, kafka, schema-registry]
    networks: [platform-net]
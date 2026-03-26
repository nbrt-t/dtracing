# ── Builder stage: full Maven build ─────────────────────────────────────────
FROM azul/zulu-openjdk:26 AS builder
WORKDIR /build

# Cache Maven wrapper + dependencies first
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY common/pom.xml              common/pom.xml
COPY market-data-handler/pom.xml market-data-handler/pom.xml
COPY book-builder/pom.xml        book-builder/pom.xml
COPY mid-pricer/pom.xml          mid-pricer/pom.xml
COPY price-tiering/pom.xml       price-tiering/pom.xml
COPY aeron-media-driver/pom.xml  aeron-media-driver/pom.xml
COPY simulator/pom.xml           simulator/pom.xml
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline -q || true

# Copy sources and build
COPY common/              common/
COPY market-data-handler/ market-data-handler/
COPY book-builder/        book-builder/
COPY mid-pricer/          mid-pricer/
COPY price-tiering/       price-tiering/
COPY aeron-media-driver/  aeron-media-driver/
COPY simulator/           simulator/
RUN ./mvnw -B clean package -DskipTests -q

# ── aeron-media-driver runtime ─────────────────────────────────────────────
FROM azul/zulu-openjdk:26-jre AS aeron-media-driver
WORKDIR /app
COPY --from=builder /build/aeron-media-driver/target/*.jar app.jar
ENTRYPOINT ["java", \
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", \
    "-Daeron.dir=/dev/shm/aeron/driver", \
    "-jar", "app.jar"]

# ── market-data-handler runtime ─────────────────────────────────────────────
FROM azul/zulu-openjdk:26-jre AS market-data-handler
WORKDIR /app
COPY --from=builder /build/market-data-handler/target/*.jar app.jar
ENTRYPOINT ["java", "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "-jar", "app.jar"]

# ── book-builder runtime ──────────────────────────────────────────────────
FROM azul/zulu-openjdk:26-jre AS book-builder
WORKDIR /app
COPY --from=builder /build/book-builder/target/*.jar app.jar
ENTRYPOINT ["java", "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "-jar", "app.jar"]

# ── mid-pricer runtime ────────────────────────────────────────────────────
FROM azul/zulu-openjdk:26-jre AS mid-pricer
WORKDIR /app
COPY --from=builder /build/mid-pricer/target/*.jar app.jar
ENTRYPOINT ["java", "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "-jar", "app.jar"]

# ── price-tiering runtime ─────────────────────────────────────────────────
FROM azul/zulu-openjdk:26-jre AS price-tiering
WORKDIR /app
COPY --from=builder /build/price-tiering/target/*.jar app.jar
ENTRYPOINT ["java", "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "-jar", "app.jar"]

# ── simulator runtime ──────────────────────────────────────────────────────
FROM azul/zulu-openjdk:26-jre AS simulator
WORKDIR /app
COPY --from=builder /build/simulator/target/*.jar app.jar
COPY simulator/data/ data/
ENTRYPOINT ["java", "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "-jar", "app.jar"]
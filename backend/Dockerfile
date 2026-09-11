FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

COPY framework-lite/pom.xml /src/framework-lite/pom.xml
COPY framework-lite/src /src/framework-lite/src
RUN mvn -f /src/framework-lite/pom.xml -DskipTests install

COPY backend/pom.xml /src/backend/pom.xml
COPY backend/src /src/backend/src
RUN mvn -f /src/backend/pom.xml -DskipTests package \
 && JAR="$(find /src/backend/target -maxdepth 1 -type f -name '*.jar' ! -name '*original*' | head -n 1)" \
 && test -n "$JAR" \
 && cp "$JAR" /src/app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /app/data/files /app/logs /app/config
COPY --from=build /src/app.jar /app/app.jar
EXPOSE 10108
ENV SPRING_AI_AGENT_STORAGE_DIRECTORY=/app/data/files
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

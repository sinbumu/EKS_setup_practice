# Stage 3: Create a minimal JRE image
FROM amazoncorretto:17-alpine3.14

WORKDIR /app
COPY /build/libs/cccv-server-v2-0.0.1-SNAPSHOT.jar .

# Expose port 8080
EXPOSE 8080

CMD ["java", "-jar", "-Drun.mode=api", "-DServer.port=8080", "cccv-server-v2-0.0.1-SNAPSHOT.jar"]

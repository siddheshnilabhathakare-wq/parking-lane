# Works on Render, Railway, Fly.io, Koyeb and any Docker host
FROM eclipse-temurin:21-jdk

WORKDIR /app
COPY . .

# Compile the server (the .jar drivers in lib/ are used when it runs)
RUN javac -cp "lib/*" Server.java

# Hosting sites set PORT automatically; the server reads it
EXPOSE 8080
CMD ["java", "-cp", ".:lib/*", "Server"]

# Android APK build image: JDK 17 + Android SDK (cmdline tools) + Gradle 8.7.
# No local Android SDK or IDE required — `docker compose up` produces APKs.
# (eclipse-temurin dropped the bookworm tag variant; jammy is the supported Debian base)
FROM eclipse-temurin:17-jdk-jammy

RUN apt-get update && \
    apt-get install -y --no-install-recommends wget unzip zip fontconfig ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# --- Android SDK -------------------------------------------------------------
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

RUN mkdir -p "$ANDROID_HOME/cmdline-tools" && \
    cd "$ANDROID_HOME/cmdline-tools" && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O tools.zip && \
    unzip -q tools.zip && mv cmdline-tools latest && rm tools.zip

RUN yes | sdkmanager --licenses > /dev/null && \
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# --- Gradle 8.7 (required by AGP 8.5; repo has no gradle wrapper) ------------
RUN wget -q https://services.gradle.org/distributions/gradle-8.7-bin.zip -O /tmp/g.zip && \
    unzip -q /tmp/g.zip -d /opt && \
    ln -s /opt/gradle-8.7/bin/gradle /usr/local/bin/gradle && \
    rm /tmp/g.zip

# --- Project sources ----------------------------------------------------------
WORKDIR /workspace
COPY . .

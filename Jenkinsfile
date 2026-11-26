// =============================================================================
// HaulMate – Jenkins pipeline (Contabo VPS), same pattern as MRPCZ.
// Checkout → build API + worker images → (re)start Redis → run API + worker on the
// shared network with posgressql_16 → health check → build + publish the Android APK
// (whenever mobile/ changed). Nginx Proxy Manager proxies cherai.nexigent-labs.co.zw → haulmate-app:8670.
// =============================================================================
pipeline {
    agent any

    triggers {
        githubPush()
        pollSCM('H/2 * * * *')
    }

    parameters {
        booleanParam(name: 'FORCE_APK', defaultValue: false,
                     description: 'Rebuild and publish the Android APK even if mobile/ has not changed')
    }

    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '3'))
        timeout(time: 75, unit: 'MINUTES')   // first APK build pulls the Flutter image + Gradle deps
    }

    environment {
        IMAGE        = 'haulmate-api'
        WORKER_IMAGE = 'haulmate-worker'
        CONTAINER    = 'haulmate-app'
        WORKER       = 'haulmate-worker'
        REDIS        = 'haulmate-redis'
        NETWORK      = 'ppe-shared-net'
        DB_CONTAINER = 'posgressql_16'
        HOST_PORT    = '8670'
        DATA_VOL     = 'haulmate-data'       // KYC images, proof of delivery (shared by API + worker)
        REDIS_VOL    = 'haulmate-redis'
        PROD_PUBLIC_URL = 'https://cherai.nexigent-labs.co.zw'
        FLUTTER_IMAGE   = 'ghcr.io/cirruslabs/flutter:stable'   // Flutter + Android SDK
        APK_BUILDER     = 'haulmate-apk-builder'
        KEYS_VOL        = 'haulmate-android-keys'   // release keystore: keep it, back it up
    }

    stages {
        stage('Checkout') {
            steps { checkout scm }
        }

        stage('Build images') {
            steps {
                sh '''
                  docker build -t ${IMAGE}:${BUILD_NUMBER} -t ${IMAGE}:latest .
                  docker build -t ${WORKER_IMAGE}:${BUILD_NUMBER} -t ${WORKER_IMAGE}:latest worker
                '''
            }
        }

        stage('Prepare network & Redis') {
            steps {
                sh '''
                  docker network inspect ${NETWORK} >/dev/null 2>&1 || docker network create ${NETWORK}
                  docker network inspect ${NETWORK} --format '{{range .Containers}}{{.Name}} {{end}}' | grep -qw ${DB_CONTAINER} \
                    || docker network connect ${NETWORK} ${DB_CONTAINER}
                  docker volume inspect ${DATA_VOL} >/dev/null 2>&1 || docker volume create ${DATA_VOL}
                  docker volume inspect ${REDIS_VOL} >/dev/null 2>&1 || docker volume create ${REDIS_VOL}
                  docker inspect ${REDIS} >/dev/null 2>&1 || docker run -d --name ${REDIS} --restart unless-stopped \
                    --network ${NETWORK} -v ${REDIS_VOL}:/data redis:7-alpine redis-server --appendonly yes
                '''
            }
        }

        stage('Deploy') {
            steps {
                sh '''
                  test -f .env || { echo ".env missing from the repository"; exit 1; }
                  docker rm -f ${CONTAINER} ${WORKER} >/dev/null 2>&1 || true
                  docker run -d --name ${CONTAINER} \
                    --restart unless-stopped \
                    --network ${NETWORK} \
                    -p 127.0.0.1:${HOST_PORT}:8670 \
                    --env-file .env \
                    -e PORT=8670 \
                    -e DB_URL=jdbc:postgresql://${DB_CONTAINER}:5432/haulmate \
                    -e PUBLIC_BASE_URL=${PROD_PUBLIC_URL} \
                    -e REDIS_HOST=${REDIS} -e REDIS_PORT=6379 \
                    -e REDIS_EMBEDDED=false -e REDIS_HEALTH_ENABLED=true -e DISCOVERY_ENABLED=false \
                    -e STORAGE_DIR=/app/data/uploads \
                    -v ${DATA_VOL}:/app/data \
                    ${IMAGE}:${BUILD_NUMBER}
                  docker run -d --name ${WORKER} \
                    --restart unless-stopped \
                    --network ${NETWORK} \
                    --env-file .env \
                    -e CELERY_BROKER_URL=redis://${REDIS}:6379/0 \
                    -e REDIS_URL=redis://${REDIS}:6379/0 \
                    -e STORAGE_DIR=/app/data/uploads \
                    -e FIREBASE_CREDENTIALS=/app/data/secrets/firebase.json \
                    -v ${DATA_VOL}:/app/data \
                    ${WORKER_IMAGE}:${BUILD_NUMBER}
                '''
            }
        }

        stage('Health check') {
            steps {
                sh '''
                  for i in $(seq 1 40); do
                    status=$(docker inspect -f '{{.State.Health.Status}}' ${CONTAINER} 2>/dev/null || echo missing)
                    echo "health: $status"
                    if [ "$status" = "healthy" ]; then
                      # new container = new IP; make Nginx Proxy Manager re-resolve haulmate-app
                      docker exec nginx-proxy-manager-npm-1 nginx -s reload 2>/dev/null || true
                      exit 0
                    fi
                    [ "$status" = "missing" ] && break
                    sleep 10
                  done
                  echo "Container did not become healthy – last 200 log lines:"
                  docker logs --tail 200 ${CONTAINER}
                  exit 1
                '''
            }
        }

        // Builds the release APK and publishes it to the running app (home page, admin
        // "Download APK", QR codes). A failure here marks the build UNSTABLE but leaves the
        // freshly deployed website/API running.
        stage('Android APK') {
            steps {
                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                    sh '''
                      APK_PATH=/app/data/uploads/apps
                      if [ "${FORCE_APK}" != "true" ] && [ -n "${GIT_PREVIOUS_SUCCESSFUL_COMMIT}" ] \
                         && git diff --quiet ${GIT_PREVIOUS_SUCCESSFUL_COMMIT} HEAD -- mobile 2>/dev/null \
                         && docker exec ${CONTAINER} test -f ${APK_PATH}/haulmate.apk; then
                        echo "mobile/ unchanged since the last successful build and an APK is already published - skipping."
                        exit 0
                      fi

                      # One-time: create the release signing key (same key every build, so updates install over the old app)
                      docker volume inspect ${KEYS_VOL} >/dev/null 2>&1 || docker volume create ${KEYS_VOL}
                      docker run --rm -v ${KEYS_VOL}:/keys eclipse-temurin:25-jre sh -c '
                        [ -f /keys/haulmate.jks ] && exit 0
                        PASS=$(head -c 48 /dev/urandom | base64 | tr -dc A-Za-z0-9 | head -c 24)
                        keytool -genkeypair -keystore /keys/haulmate.jks -alias haulmate -keyalg RSA -keysize 2048 -validity 10000 \
                          -storepass "$PASS" -keypass "$PASS" -dname "CN=HaulMate, O=HaulMate, L=Harare, C=ZW"
                        printf "storePassword=%s\nkeyPassword=%s\nkeyAlias=haulmate\nstoreFile=/keys/haulmate.jks\n" "$PASS" "$PASS" > /keys/key.properties
                        echo "Created new Android release key"'

                      APK_NAME=$(grep -E '^version:' mobile/pubspec.yaml | sed 's/version: *//' | cut -d+ -f1 | tr -d ' \r')
                      echo "Building HaulMate ${APK_NAME} (build ${BUILD_NUMBER}) for ${PROD_PUBLIC_URL}"

                      docker rm -f ${APK_BUILDER} >/dev/null 2>&1 || true
                      trap 'docker rm -f ${APK_BUILDER} >/dev/null 2>&1 || true' EXIT
                      docker pull -q ${FLUTTER_IMAGE}
                      docker run -d --name ${APK_BUILDER} --memory=6g \
                        -v ${KEYS_VOL}:/keys:ro \
                        -v haulmate-gradle-cache:/root/.gradle \
                        -v haulmate-pub-cache:/root/.pub-cache \
                        ${FLUTTER_IMAGE} sleep 7200
                      docker exec ${APK_BUILDER} mkdir -p /src
                      docker cp mobile/. ${APK_BUILDER}:/src
                      docker exec -e APK_NAME="${APK_NAME}" -e APK_CODE="${BUILD_NUMBER}" -e API_BASE="${PROD_PUBLIC_URL}" ${APK_BUILDER} sh -c '
                        set -e
                        cd /src
                        cp /keys/key.properties android/key.properties
                        # VPS-friendly Gradle settings (the dev PC uses 8 GB)
                        sed -i "s/^org.gradle.jvmargs=.*/org.gradle.jvmargs=-Xmx3G -XX:MaxMetaspaceSize=1G/; s/^org.gradle.daemon=.*/org.gradle.daemon=false/" android/gradle.properties
                        rm -rf build .dart_tool android/local.properties
                        git config --global --add safe.directory "*" || true
                        flutter --version
                        flutter pub get
                        flutter build apk --release --build-name="$APK_NAME" --build-number="$APK_CODE" --dart-define=API_BASE="$API_BASE"'

                      docker cp ${APK_BUILDER}:/src/build/app/outputs/flutter-apk/app-release.apk ./haulmate.apk
                      ls -lh haulmate.apk
                      echo "${APK_NAME} (build ${BUILD_NUMBER})" > haulmate.version

                      # Publish to the running app (persistent haulmate-data volume); swap in atomically
                      docker exec ${CONTAINER} mkdir -p ${APK_PATH}
                      docker cp haulmate.apk ${CONTAINER}:${APK_PATH}/haulmate.apk.new
                      docker cp haulmate.version ${CONTAINER}:${APK_PATH}/haulmate.version
                      docker exec -u 0 ${CONTAINER} sh -c "chown haulmate:root ${APK_PATH}/haulmate.apk.new ${APK_PATH}/haulmate.version \
                        && mv -f ${APK_PATH}/haulmate.apk.new ${APK_PATH}/haulmate.apk"
                      echo "Published: ${PROD_PUBLIC_URL}/download/android"
                    '''
                    archiveArtifacts artifacts: 'haulmate.apk', fingerprint: true
                }
            }
        }
    }

    post {
        success {
            sh '''
              for img in ${IMAGE} ${WORKER_IMAGE}; do
                docker images $img --format '{{.Tag}}' | grep -E '^[0-9]+$' | sort -rn | tail -n +4 \
                  | xargs -r -I{} docker rmi $img:{} || true
              done
            '''
        }
    }
}

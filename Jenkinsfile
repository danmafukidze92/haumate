// =============================================================================
// HaulMate – Jenkins pipeline (Contabo VPS), same pattern as MRPCZ.
// Checkout → build API + worker images → (re)start Redis → run API + worker on the
// shared network with posgressql_16 → health check. nginx proxies
// cherai.nexigent-labs.co.zw → 127.0.0.1:8670.
// =============================================================================
pipeline {
    agent any

    triggers {
        githubPush()
        pollSCM('H/2 * * * *')
    }

    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timeout(time: 40, unit: 'MINUTES')
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

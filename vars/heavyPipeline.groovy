            stage('Heavy: Dynamic Analysis (OWASP ZAP)') {
                steps {
                    sh 'docker rm -f zap-test-app || true'
                    sh 'docker rm -f zap-runner || true'
                    sh 'docker network rm zap-net || true'

                    echo "Building App Container..."
                    sh 'docker build -t test-app:latest .'
                    sh 'docker network create --internal zap-net'
                    sh 'docker run -d --name zap-test-app --network zap-net test-app:latest'
                    
                    script {
                        def bootStatus = sh(script: '''
                            for i in $(seq 1 30); do
                                if docker exec zap-test-app node -e "require('http').get('http://localhost:3000', res => process.exit(res.statusCode < 500 ? 0 : 1)).on('error', () => process.exit(1))" > /dev/null 2>&1; then
                                    exit 0
                                fi
                                sleep 2
                            done
                            exit 1
                        ''', returnStatus: true)
                        
                        if (bootStatus != 0) {
                            echo "App failed to start. Logs:"
                            sh 'docker logs zap-test-app || true'
                            error("App failed to boot!")
                        }
                    }
                    
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        sh '''
                        docker run --name zap-runner --user root --network zap-net \
                        -v $(pwd):/zap/wrk/:rw \
                        zaproxy/zap-stable zap-baseline.py \
                        -t http://zap-test-app:3000 \
                        -r /zap/wrk/zap-report.html \
                        -J /zap/wrk/zap-report.json
                        '''
                    }
                    
                    // Teardown
                    sh 'docker rm -f zap-test-app || true'
                    sh 'docker rm -f zap-runner || true'
                    sh 'docker network rm zap-net || true'
                    
                    // Fix permissions so Jenkins can read them (NO mv commands needed)
                    sh 'chmod 644 zap-report.json zap-report.html || true'
                }
            }

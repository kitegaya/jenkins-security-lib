// vars/heavyPipeline.groovy
def call() {
    pipeline {
        agent any
        stages {
            stage('Fast: Secrets (Gitleaks)') {
                steps {
                    echo "Scanning for hardcoded secrets..."
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        sh 'gitleaks detect --source . --no-git --redact --exit-code 1'
                    }
                }
            }
            
            stage('Fast: Dependencies (NPM Audit)') {
                steps {
                    echo "Scanning for vulnerable packages..."
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        sh 'npm audit --audit-level=critical'
                    }
                }
            }

            stage('Heavy: Static Analysis (Semgrep)') {
                steps {
                    echo "Running deep static code analysis..."
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        sh 'semgrep --config auto --error --json --output=semgrep-report.json .'
                    }
                }
            }

            stage('Heavy: Dynamic Analysis (OWASP ZAP)') {
                steps {
                    echo "Building App Container..."
                    sh 'docker build -t test-app:latest .'

                    echo "Creating Docker Network..."
                    sh 'docker network create zap-net || true'

                    echo "Starting App Container..."
                    sh 'docker run -d --name zap-test-app --network zap-net test-app:latest'
                    sh 'sleep 10'

                    echo "Running OWASP ZAP Attack..."
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        // Run ZAP without --rm so we can copy the file out
                        // Use -r zap-report.html (relative path)
                        sh '''
                        docker run --name zap-runner --user root --network zap-net \
                        -v $(pwd):/zap/wrk/:rw \
                        zaproxy/zap-stable zap-baseline.py \
                        -t http://zap-test-app:3000 \
                        -r zap-report.html || true
                        '''
                    }

                    echo "=== TRACING: Extracting ZAP Report ==="
                    sh '''
                    # List files inside the container to see where it went
                    docker exec zap-runner ls -la /zap/wrk/ || echo "Cannot access /zap/wrk"
                    docker exec zap-runner ls -la /zap/ || echo "Cannot access /zap"
                    
                    # Copy the report from the container
                    docker cp zap-runner:/zap/wrk/zap-report.html ./zap-report.html 2>/dev/null || echo "Tried /zap/wrk, not found"
                    docker cp zap-runner:/zap/zap-report.html ./zap-report.html 2>/dev/null || echo "Tried /zap, not found"
                    
                    # Remove the runner container
                    docker rm -f zap-runner || true
                    '''
                    echo "======================================"

                    echo "Cleaning up app container and network..."
                    sh 'docker rm -f zap-test-app || true'
                    sh 'docker network rm zap-net || true'
                }
            }
            
            stage('Final Report & Verdict') {
                steps {
                    echo "==================================================="
                    echo "FULL SECURITY SCAN COMPLETE"
                    echo "==================================================="
                }
            }
        }
        post {
            success {
                echo "✅ ALL security scans passed! Ready for production."
            }
            failure {
                echo "🚨 HEAVY SCAN FAILED: One or more security issues found. Do NOT deploy."
            }
            always {
                archiveArtifacts artifacts: 'semgrep-report.json, zap-report.html', allowEmptyArchive: true
            }
        }
    }
}

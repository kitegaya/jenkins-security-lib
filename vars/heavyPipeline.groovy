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
                        sh 'npm audit --audit-level=high'
                    }
                }
            }

            stage('Heavy: Static Analysis (Semgrep)') {
                steps {
                    echo "Running deep static code analysis..."
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        sh 'semgrep --config auto --error --json --output=semgrep-report.json . || true'
                    }
                }
            }

            stage('Heavy: Dynamic Analysis (OWASP ZAP)') {
                steps {
                    echo "Building App Container..."
                    sh 'docker build -t test-app:latest .'

                    echo "Creating Sealed Docker Network..."
                    sh 'docker network create --internal zap-sealed-net || true'

                    echo "Starting App Container..."
                    sh 'docker run -d --name zap-test-app --network zap-sealed-net test-app:latest'

                    echo "Running OWASP ZAP Attack..."
                    // We use || true so the pipeline continues to the report stage even if ZAP finds issues
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        sh '''
                        docker run --rm --network zap-sealed-net \
                        -v $(pwd):/zap/wrk/:rw \
                        ghcr.io/zaproxy/zap-stable zap-baseline.py \
                        -t http://zap-test-app:3000 \
                        -r zap-report.html || true
                        '''
                    }

                    echo "Cleaning up Docker resources..."
                    sh 'docker rm -f zap-test-app || true'
                    sh 'docker network rm zap-sealed-net || true'
                }
            }
            
            stage('Final Report & Verdict') {
                steps {
                    echo "==================================================="
                    echo "FULL SECURITY SCAN COMPLETE"
                    echo "==================================================="
                    echo "Check the stages above for any RED (Failed) results."
                    echo "If any stage is RED, the pipeline is marked FAILURE and will not deploy."
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

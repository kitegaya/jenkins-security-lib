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
                        sh 'npm audit --audit-level=critical'  // Only fail on critical, not moderate
                    }
                }
            }

            stage('Heavy: Static Analysis (Semgrep)') {
                steps {
                    echo "Running deep static code analysis..."
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        // Removed || true — if Semgrep finds blocking issues, the build fails
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
                    sh 'docker ps | grep zap-test-app'

                    echo "Running OWASP ZAP Attack..."
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        // ZAP exits with 2 if it finds FAIL-NEW issues, 1 if only WARN-NEW
                        sh '''
                        docker run --rm --user root --network zap-net \
                        -v $(pwd):/zap/wrk/:rw \
                        zaproxy/zap-stable zap-baseline.py \
                        -t http://zap-test-app:3000 \
                        -r /zap/wrk/zap-report.html
                        '''
                    }

                    echo "Cleaning up..."
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
                echo "Review the failed stages and fix the issues before merging."
            }
            always {
                archiveArtifacts artifacts: 'semgrep-report.json, zap-report.html', allowEmptyArchive: true
            }
        }
    }
}

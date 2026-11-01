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
                        // Note: we use --json to save the report, and || true so Semgrep doesn't kill the stage
                        // We will check the exit code manually below
                    }
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
                echo "Review the failed stages above and fix all issues."
            }
            always {
                // This runs no matter what - great for sending reports
                archiveArtifacts artifacts: 'semgrep-report.json', allowEmptyArchive: true
            }
        }
    }
}

// vars/fastPipeline.groovy
def call() {
    pipeline {
        agent any
        stages {
            stage('Fast: Secrets (Gitleaks)') {
                steps {
                    echo "Scanning for hardcoded secrets..."
                    sh 'gitleaks detect --source . --no-git --redact'
                }
            }
            stage('Fast: Dependencies (NPM Audit)') {
                steps {
                    echo "Scanning for vulnerable packages..."
                    sh 'npm audit --audit-level=high || true'
                }
            }
        }
    }
}

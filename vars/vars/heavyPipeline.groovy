// vars/heavyPipeline.groovy
def call() {
    pipeline {
        agent any
        stages {
            stage('Heavy: Static Analysis (Semgrep)') {
                steps {
                    echo "Running deep static code analysis..."
                    sh 'semgrep --config auto --error .'
                }
            }
            // We will add the OWASP ZAP Docker stage here later
        }
        post {
            failure {
                echo "🚨 HEAVY SCAN FAILED: Do not deploy to production!"
            }
        }
    }
}

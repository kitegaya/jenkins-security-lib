// vars/heavyPipeline.groovy
def call() {
    pipeline {
        agent any
        
        environment {
            SCAN_FAILED = 'false'
        }
        
        stages {
            stage('Fast: Secrets (Gitleaks)') {
                steps {
                    script {
                        def status = sh(script: 'gitleaks detect --source . --no-git --redact --exit-code 1', returnStatus: true)
                        if (status != 0) {
                            env.SCAN_FAILED = 'true'
                            currentBuild.result = 'FAILURE'
                            error("Gitleaks found secrets!")
                        }
                    }
                }
            }
            
            stage('Fast: Dependencies (NPM Audit)') {
                steps {
                    script {
                        def status = sh(script: 'npm audit --audit-level=critical', returnStatus: true)
                        if (status != 0) {
                            env.SCAN_FAILED = 'true'
                            currentBuild.result = 'FAILURE'
                            error("NPM Audit found critical vulnerabilities!")
                        }
                    }
                }
            }

            stage('Heavy: Static Analysis (Semgrep)') {
                steps {
                    script {
                        def status = sh(script: 'semgrep --config auto --error --json --output=semgrep-report.json .', returnStatus: true)
                        if (status != 0) {
                            env.SCAN_FAILED = 'true'
                            currentBuild.result = 'FAILURE'
                            error("Semgrep found issues!")
                        }
                    }
                }
            }

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
                            env.SCAN_FAILED = 'true'
                            currentBuild.result = 'FAILURE'
                        }
                    }
                    
                    script {
                        def zapStatus = sh(script: '''
                        docker run --name zap-runner --user root --network zap-net \
                        -v $(pwd):/zap/wrk/:rw \
                        zaproxy/zap-stable zap-baseline.py \
                        -t http://zap-test-app:3000 \
                        -r zap-report.html \
                        -J zap-report.json
                        ''', returnStatus: true)
                        
                        if (zapStatus != 0) {
                            env.SCAN_FAILED = 'true'
                            currentBuild.result = 'FAILURE'
                        }
                    }
                    
                    sh 'docker rm -f zap-test-app || true'
                    sh 'docker rm -f zap-runner || true'
                    sh 'docker network rm zap-net || true'
                    sh 'chmod 644 zap-report.json zap-report.html || true'
                }
            }
            
            stage('Generate Unified Report') {
                steps {
                    writeFile file: 'generate_report.py', text: libraryResource('generate_report.py')
                    sh 'python3 generate_report.py'
                    sh 'rm generate_report.py'
                }
            }

            // --- THE HUMAN GATE ---
            stage('Manual Approval Gate') {
                when { expression { env.SCAN_FAILED == 'true' } }
                steps {
                    script {
                        try {
                            telegramNotify("🚨 <b>HEAVY SCAN FAILED</b>\nRepo: ${env.JOB_NAME}\nIssues found. See attached PDF.", "security-report.pdf")
                        } catch (e) {
                            echo "Telegram notification failed: ${e.message}"
                        }
                        
                        try {
                            timeout(time: 1, unit: 'HOURS') {
                                input message: 'Security issues found. Force deploy to PRODUCTION?', ok: 'Proceed'
                            }
                            currentBuild.result = 'UNSTABLE' 
                        } catch (err) {
                            telegramNotify("❌ Deployment Aborted for ${env.JOB_NAME}.")
                            error "Pipeline aborted by user or timeout."
                        }
                    }
                }
            }
            
            // --- DEPLOYMENT ---
            stage('Deploy to Production') {
                // Only deploy if scan passed OR if human approved (build is UNSTABLE)
                when { expression { env.SCAN_FAILED == 'false' || currentBuild.result == 'UNSTABLE' } }
                steps {
                    echo "Deploying to Production..."
                    // Add your real deployment commands here
                    
                    telegramNotify("✅ <b>Deployment Successful</b>\nRepo: ${env.JOB_NAME} is now live in Production.")
                }
            }
        }
        post {
            always {
                archiveArtifacts artifacts: 'semgrep-report.json, zap-report.json, zap-report.html, security-report.pdf', allowEmptyArchive: true
            }
        }
    }
}

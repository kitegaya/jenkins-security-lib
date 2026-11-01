// vars/heavyPipeline.groovy
def call() {
    pipeline {
        agent any
        stages {
            stage('Fast: Secrets (Gitleaks)') {
                steps {
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        sh 'gitleaks detect --source . --no-git --redact --exit-code 1'
                    }
                }
            }
            
            stage('Fast: Dependencies (NPM Audit)') {
                steps {
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        sh 'npm audit --audit-level=critical'
                    }
                }
            }

            stage('Heavy: Static Analysis (Semgrep)') {
                steps {
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        sh 'semgrep --config auto --error --json --output=semgrep-report.json .'
                    }
                }
            }

            stage('Heavy: Dynamic Analysis (OWASP ZAP)') {
                steps {
                    // --- ADD THESE 3 LINES TO CLEAN UP ZOMBIE CONTAINERS ---
                    sh 'docker rm -f zap-test-app || true'
                    sh 'docker rm -f zap-runner || true'
                    sh 'docker network rm zap-net || true'
                    // -------------------------------------------------------

                    echo "Building App Container..."
                    sh 'docker build -t test-app:latest .'
                    // ... rest of the stage
                    sh 'docker build -t test-app:latest .'
                    sh 'docker network create zap-net || true'
                    sh 'docker run -d --name zap-test-app --network zap-net test-app:latest'
                    sh 'sleep 10'
                    
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        sh '''
                        docker run --name zap-runner --user root --network zap-net \
                        -v $(pwd):/zap/wrk/:rw \
                        zaproxy/zap-stable zap-baseline.py \
                        -t http://zap-test-app:3000 \
                        -r zap-report.html \
                        -J zap-report.json
                        '''
                    }
                    sh 'docker rm -f zap-test-app || true'
                    sh 'docker rm -f zap-runner || true'
                    sh 'docker network rm zap-net || true'
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
                // Only pause if the build previously FAILED
                when { expression { currentBuild.result == 'FAILURE' } }
                steps {
                    script {
                        telegramNotify("🚨 <b>HEAVY SCAN FAILED</b>\nRepo: ${env.JOB_NAME}\nIssues found. See attached PDF.", "security-report.pdf")
                        
                        try {
                            timeout(time: 1, unit: 'HOURS') {
                                input message: 'Security issues found. Force deploy to PRODUCTION?', ok: 'Proceed'
                            }
                        } catch (err) {
                            telegramNotify("❌ Deployment Aborted for ${env.JOB_NAME}.")
                            error "Pipeline aborted by user or timeout."
                        }
                    }
                }
            }
            
            // --- DEPLOYMENT ---
            stage('Deploy to Production') {
                steps {
                    echo "Deploying to Production..."
                    // Add your real deployment commands here (e.g., rsync, ssh pm2 restart)
                    // sh 'rsync ...'
                    
                    telegramNotify("✅ <b>Deployment Successful</b>\nRepo: ${env.JOB_NAME} is now live in Production.")
                }
            }
        }
        post {
            always {
                archiveArtifacts artifacts: 'semgrep-report.json, zap-report.json, security-report.pdf', allowEmptyArchive: true
            }
        }
    }
}

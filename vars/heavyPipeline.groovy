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
                    
                    sh 'docker rm -f zap-test-app || true'
                    sh 'docker rm -f zap-runner || true'
                    sh 'docker network rm zap-net || true'
                    
                    // Debug and Permissions
                    sh 'ls -la'
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
                // Check native Jenkins failure status
                when { expression { currentBuild.currentResult == 'FAILURE' } }
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
                            // Override the FAILURE status since a human approved it
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
                // Only deploy if the build is UNSTABLE (human approved) or SUCCESS (passed all checks)
                when { expression { currentBuild.result == 'UNSTABLE' || currentBuild.currentResult == 'SUCCESS' } }
                steps {
                    echo "Deploying to Production..."
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

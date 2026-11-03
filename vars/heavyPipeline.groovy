// vars/heavyPipeline.groovy
def call() {
    pipeline {
        agent any
        
        // Create our own flag to track if a scan failed
        environment {
            SCAN_FAILED = 'false'
        }
        
        stages {
            stage('Fast: Secrets (Gitleaks)') {
                steps {
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        sh 'gitleaks detect --source . --no-git --redact --exit-code 1'
                        script { env.SCAN_FAILED = 'true' }
                    }
                }
            }
            
            stage('Fast: Dependencies (NPM Audit)') {
                steps {
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        sh 'npm audit --audit-level=critical'
                        script { env.SCAN_FAILED = 'true' }
                    }
                }
            }

            stage('Heavy: Static Analysis (Semgrep)') {
                steps {
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        sh 'semgrep --config auto --error --json --output=semgrep-report.json .'
                        script { env.SCAN_FAILED = 'true' }
                    }
                }
            }

            stage('Heavy: Dynamic Analysis (OWASP ZAP)') {
                steps {
                    // Clean up zombies from previous failed runs
                    sh 'docker rm -f zap-test-app || true'
                    sh 'docker rm -f zap-runner || true'
                    sh 'docker network rm zap-net || true'

                    echo "Building App Container..."
                    sh 'docker build -t test-app:latest .'
                    
                    echo "Starting Isolated Network..."
                    sh 'docker network create --internal zap-net'
                    
                    echo "Starting App Container..."
                    sh 'docker run -d --name zap-test-app --network zap-net test-app:latest'
                    
                    // Robust wait: Use Node.js to check the app
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        echo "Waiting for App to boot..."
                        sh '''
                            for i in $(seq 1 30); do
                                if docker exec zap-test-app node -e "require('http').get('http://localhost:3000', res => process.exit(res.statusCode < 500 ? 0 : 1)).on('error', () => process.exit(1))" > /dev/null 2>&1; then
                                    echo "App is up!"
                                    exit 0
                                fi
                                sleep 2
                            done
                            echo "App failed to start in time. Printing logs:"
                            docker logs zap-test-app
                            exit 1
                        '''
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
                        script { env.SCAN_FAILED = 'true' }
                    }
                    
                    // Teardown & Fix Permissions
                    sh 'docker rm -f zap-test-app || true'
                    sh 'docker rm -f zap-runner || true'
                    sh 'docker network rm zap-net || true'
                    
                    // CRITICAL FIX: Make sure Jenkins can read the files ZAP created as root
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
                // Use our custom environment variable instead of currentBuild.currentResult
                when { expression { env.SCAN_FAILED == 'true' } }
                steps {
                    script {
                        try {
                            telegramNotify("🚨 <b>HEAVY SCAN FAILED</b>\nRepo: ${env.JOB_NAME}\nIssues found. See attached PDF.", "security-report.pdf")
                        } catch (e) {
                            echo "Telegram notification failed, but continuing to gate: ${e.message}"
                        }
                        
                        try {
                            // This pauses the build in the Jenkins UI
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

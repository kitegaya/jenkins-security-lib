def call() {
    // 🛑 THE KILL SWITCH
    // Set to 'true' to pause all security scans and let developers push freely.
    // Set to 'false' to turn the security pipeline back on.
    def PIPELINE_PAUSED = true 
    
    if (PIPELINE_PAUSED) {
        echo "🛑 SECURITY PIPELINE IS PAUSED. Code is passing without scans."
        return // This stops the pipeline immediately and marks it as SUCCESS.
    }

    if (env.BRANCH_NAME == 'main') {
        heavyPipeline()
    } else {
        fastPipeline()
    }
}

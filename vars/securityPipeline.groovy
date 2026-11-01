def call() {
    // Set the Telegram Chat ID here so devs don't have to
    env.TELEGRAM_CHAT_ID = '-5399837097' // Replace with your actual ID

    if (env.BRANCH_NAME == 'main' || env.BRANCH_NAME == 'master') {
        heavyPipeline()
    } else {
        fastPipeline()
    }
}

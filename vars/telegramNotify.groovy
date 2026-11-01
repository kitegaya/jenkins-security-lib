def call(String message, String filePath = '') {
    withCredentials([string(credentialsId: 'telegram-bot-token', variable: 'TELEGRAM_TOKEN')]) {
        if (filePath == '') {
            sh """
            curl -s -X POST "https://api.telegram.org/bot\${TELEGRAM_TOKEN}/sendMessage" \
            -d chat_id="${env.TELEGRAM_CHAT_ID}" \
            -d text="${message}" \
            -d parse_mode="HTML"
            """
        } else {
            sh """
            curl -s -X POST "https://api.telegram.org/bot\${TELEGRAM_TOKEN}/sendDocument" \
            -F chat_id="${env.TELEGRAM_CHAT_ID}" \
            -F document=@\${filePath} \
            -F caption="${message}" \
            -F parse_mode="HTML"
            """
        }
    }
}

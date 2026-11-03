def call(String message, String filePath = '') {
    withCredentials([string(credentialsId: 'telegram-bot-token', variable: 'TELEGRAM_TOKEN')]) {
        if (filePath == '') {
            sh """
                curl -s -X POST "https://api.telegram.org/bot\${TELEGRAM_TOKEN}/sendMessage" \
                --data-urlencode "chat_id=${env.TELEGRAM_CHAT_ID}" \
                --data-urlencode "text=${message}" \
                --data-urlencode "parse_mode=HTML" || true
            """
        } else {
            sh """
                curl -s -X POST "https://api.telegram.org/bot\${TELEGRAM_TOKEN}/sendDocument" \
                -F "chat_id=${env.TELEGRAM_CHAT_ID}" \
                -F "document=@${filePath}" \
                --form-string "caption=${message}" \
                -F "parse_mode=HTML" || true
            """
        }
    }
}

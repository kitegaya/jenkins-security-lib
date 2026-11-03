def call(String message, String filePath = '', String replyMarkup = '') {
    withEnv(["MSG=${message}", "CHAT_ID=${env.TELEGRAM_CHAT_ID}", "FILE_PATH=${filePath}", "REPLY_MARKUP=${replyMarkup}"]) {
        withCredentials([string(credentialsId: 'telegram-bot-token', variable: 'TELEGRAM_TOKEN')]) {
            if (filePath == '') {
                sh '''
                    curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_TOKEN}/sendMessage" \
                    --data-urlencode "chat_id=${CHAT_ID}" \
                    --data-urlencode "text=${MSG}" \
                    --data-urlencode "parse_mode=HTML" \
                    ${REPLY_MARKUP:+--data-urlencode "reply_markup=${REPLY_MARKUP}"} || true
                '''
            } else {
                sh '''
                    curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_TOKEN}/sendDocument" \
                    -F "chat_id=${CHAT_ID}" \
                    -F "document=@${FILE_PATH}" \
                    --form-string "caption=${MSG}" \
                    -F "parse_mode=HTML" \
                    ${REPLY_MARKUP:+-F "reply_markup=${REPLY_MARKUP}"} || true
                '''
            }
        }
    }
}

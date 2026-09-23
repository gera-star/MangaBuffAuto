package com.example.mangabuffauto.automation

object ProtectionGuard {
    fun checkBlock(url: String, html: String?): AutomationState? {
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains("captcha") || lowerUrl.contains("cloudflare")) return AutomationState.CAPTCHA
        
        html?.let {
            val lowerHtml = it.lowercase()
            if (lowerHtml.contains("verify you are human") || 
                lowerHtml.contains("checking your browser") ||
                lowerHtml.contains("one more step") ||
                lowerHtml.contains("please wait...")) return AutomationState.CAPTCHA
                
            if (lowerHtml.contains("access denied") || 
                lowerHtml.contains("request blocked") ||
                lowerHtml.contains("forbidden") ||
                lowerHtml.contains("too many requests") ||
                lowerHtml.contains("ip address is blocked")) return AutomationState.BLOCKED
        }
        return null
    }

    fun checkHttpStatus(code: Int): AutomationState? {
        return when (code) {
            403 -> AutomationState.BLOCKED
            429 -> AutomationState.BLOCKED
            else -> null
        }
    }
}

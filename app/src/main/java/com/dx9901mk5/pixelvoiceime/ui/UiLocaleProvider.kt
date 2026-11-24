package com.dx9901mk5.pixelvoiceime.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.dx9901mk5.pixelvoiceime.data.AppUiLanguage
import java.util.Locale

val LocalHostActivity = staticCompositionLocalOf<Activity?> { null }

object UiLocaleHelper {
    fun resolveLocale(uiLanguage: AppUiLanguage): Locale? = when (uiLanguage) {
        AppUiLanguage.SYSTEM -> null
        AppUiLanguage.ZH_TW -> Locale.TRADITIONAL_CHINESE
        AppUiLanguage.ZH_CN -> Locale.SIMPLIFIED_CHINESE
        AppUiLanguage.EN -> Locale.ENGLISH
        AppUiLanguage.JA -> Locale.JAPANESE
    }

    fun localizedContext(base: Context, uiLanguage: AppUiLanguage): Context {
        val target = resolveLocale(uiLanguage) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(target)
        config.setLocales(LocaleList(target))
        return base.createConfigurationContext(config)
    }
}

@Composable
fun ProvideUiLanguage(
    uiLanguage: AppUiLanguage,
    hostActivity: Activity? = null,
    content: @Composable () -> Unit
) {
    val baseContext = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val localizedCtx = remember(baseContext, baseConfig, uiLanguage) {
        UiLocaleHelper.localizedContext(baseContext, uiLanguage)
    }
    CompositionLocalProvider(
        LocalHostActivity provides (hostActivity ?: LocalHostActivity.current ?: (baseContext as? Activity)),
        LocalContext provides localizedCtx,
        LocalConfiguration provides localizedCtx.resources.configuration
    ) {
        content()
    }
}

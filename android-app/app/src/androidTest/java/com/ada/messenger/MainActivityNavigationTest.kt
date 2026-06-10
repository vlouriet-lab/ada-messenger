package com.ada.messenger

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val permissions: GrantPermissionRule? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        null
    }

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(AppDataResetRule())
        .around(composeRule)

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun cleanLaunchShowsPatternRegistration() {
        composeRule.onNodeWithText(context.getString(R.string.pattern_register_step1_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.recovery_import_open_button))
            .assertIsDisplayed()
    }

    @Test
    fun recoverAccountNavigatesToRecoveryImport() {
        composeRule.onNodeWithText(context.getString(R.string.recovery_import_open_button))
            .performClick()

        composeRule.onNodeWithText(context.getString(R.string.recovery_import_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.recovery_import_tab_file))
            .assertIsDisplayed()
    }

    @Test
    fun recoveryImportCanSwitchToCodeMode() {
        composeRule.onNodeWithText(context.getString(R.string.recovery_import_open_button))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.recovery_import_tab_code))
            .performClick()

        composeRule.onNodeWithText(context.getString(R.string.recovery_import_display_name_label))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.recovery_import_code_confirm_button))
            .assertIsDisplayed()
    }
}

private class AppDataResetRule : ExternalResource() {
    override fun before() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.filesDir.deleteRecursively()
        context.cacheDir.deleteRecursively()
        context.codeCacheDir?.deleteRecursively()
        context.noBackupFilesDir?.deleteRecursively()
        context.getSharedPreferences("ada_theme", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ada_lock", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ada_secure", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ada_profile", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
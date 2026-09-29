package com.cognidiary.app.ui.family

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.ui.components.BackButton
import com.cognidiary.app.ui.components.InfoBlock
import com.cognidiary.app.ui.components.PrimaryButton
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.StatusReferral
import com.cognidiary.app.ui.theme.TextPrimary

/**
 * P-F10 家属端查看密码页。
 *
 * 规则（负责人 2026-09-28 指示）：
 *   - 已登录状态下进入家属端：只验证查看密码（家属自设，预置 000000），【不要求账号】
 *   - 完整登录（账号+密码）只在第一次使用和退出登录后出现
 *   - 查看密码属于账号体系的一部分，可在家属端设置中修改
 *   - 目的：防止老人随手查看家属端内容
 */
@Composable
fun FamilyPinScreen(
    onBack: () -> Unit,
    onUnlocked: () -> Unit,
) {
    val prefs = ServiceLocator.prefs

    var pin by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = ScreenPadding.family, vertical = Spacing.x4),
    ) {
        BackButton(onBack = onBack)
        Spacer(modifier = Modifier.height(Spacing.x2))

        Text(
            text = Copy.Family.PIN_TITLE,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(Spacing.x6))

        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it },
            label = { Text(text = Copy.Family.PIN_LABEL) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )

        if (error != null) {
            Spacer(modifier = Modifier.height(Spacing.x3))
            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = StatusReferral,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.x6))
        PrimaryButton(
            text = Copy.Consent.CONFIRM,
            onClick = {
                if (prefs.verifyFamilyPin(pin)) {
                    onUnlocked()
                } else {
                    error = Copy.Family.PIN_WRONG
                }
            },
            enabled = pin.isNotEmpty(),
        )

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(Spacing.x4))
        InfoBlock(text = Copy.Family.PIN_HINT)
        Spacer(modifier = Modifier.height(Spacing.x4))
    }
}

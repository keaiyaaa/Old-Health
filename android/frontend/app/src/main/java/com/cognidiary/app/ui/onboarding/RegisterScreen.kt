package com.cognidiary.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.data.model.UserMode
import com.cognidiary.app.data.remote.ApiException
import com.cognidiary.app.ui.components.BackButton
import com.cognidiary.app.ui.components.PrimaryButton
import com.cognidiary.app.ui.components.SecondaryButton
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.StatusReferral
import kotlinx.coroutines.launch

/**
 * 注册页（原型图 2）：账号名称 / 邮箱 / 密码 / 邮箱验证码 → 注册。
 *
 * 后端契约（API-01 1.2）：密码至少 8 位；验证码 scene=REGISTER；
 * 性别必填（MALE/FEMALE，用于「儿子/女儿」显示），原型未画但契约要求。
 * 成功后自动登录并进入对应端。
 */
@Composable
fun RegisterScreen(
    mode: UserMode,
    onBack: () -> Unit,
    onRegistered: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var genderMale by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun sendCode() {
        error = null
        busy = true
        scope.launch {
            try {
                ServiceLocator.auth.sendEmailCode(email, "REGISTER")
                error = Copy.Login.CODE_SENT
                busy = false
            } catch (e: Exception) {
                error = (e as? ApiException)?.message ?: "网络不给力，请稍后再试"
                busy = false
            }
        }
    }

    fun submit() {
        error = null
        if (password.length < 8) {
            error = Copy.Login.REGISTER_SHORT
            return
        }
        busy = true
        scope.launch {
            try {
                val info = ServiceLocator.auth.register(
                    username = username, email = email, code = code,
                    password = password,
                    gender = if (genderMale) "MALE" else "FEMALE")
                if (info == null) {
                    error = Copy.Login.WRONG
                    busy = false
                    return@launch
                }
                if (mode != UserMode.ELDER) {
                    busy = false
                    onRegistered()
                    return@launch
                }
                // 老人端：有绑定的老人则直接锁定；没有则进首页后再提示绑定
                val subjects = ServiceLocator.auth.listSubjects()
                if (subjects.isNotEmpty()) {
                    ServiceLocator.auth.lockSubject(subjects.first().subjectId)
                }
                busy = false
                onRegistered()
            } catch (e: Exception) {
                error = (e as? ApiException)?.message ?: "网络不给力，请稍后再试"
                busy = false
            }
        }
    }

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
            text = Copy.Login.REGISTER_TITLE,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(Spacing.x6))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(text = Copy.Login.USERNAME_LABEL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Spacing.x3))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(text = Copy.Login.EMAIL_LABEL) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Spacing.x3))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(text = Copy.Login.PASSWORD_LABEL) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Spacing.x3))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(text = Copy.Login.EMAIL_CODE_LABEL) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Spacing.x3))
        SecondaryButton(
            text = Copy.Login.SEND_CODE_BUTTON,
            onClick = { sendCode() },
            enabled = email.isNotBlank() && !busy,
        )
        Spacer(modifier = Modifier.height(Spacing.x3))
        // 契约必填：性别决定「儿子/女儿」显示（API-01 1.2）
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            FilterChip(
                selected = genderMale,
                onClick = { genderMale = true },
                label = { Text(text = Copy.Login.GENDER_SON) },
            )
            FilterChip(
                selected = !genderMale,
                onClick = { genderMale = false },
                label = { Text(text = Copy.Login.GENDER_DAUGHTER) },
            )
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(Spacing.x3))
            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = StatusReferral,
            )
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(Spacing.x6))
        PrimaryButton(
            text = Copy.Login.REGISTER_SUBMIT,
            onClick = { submit() },
            enabled = username.isNotBlank() && email.isNotBlank() &&
                password.length >= 8 && code.isNotBlank() && !busy,
        )
        Spacer(modifier = Modifier.height(Spacing.x4))
    }
}

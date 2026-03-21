package com.udnahc.immichgallery.ui.screen.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.app_title
import immichgallery.composeapp.generated.resources.login_api_key_label
import immichgallery.composeapp.generated.resources.login_api_key_placeholder
import immichgallery.composeapp.generated.resources.login_connect
import immichgallery.composeapp.generated.resources.login_server_url_label
import immichgallery.composeapp.generated.resources.login_server_url_placeholder
import immichgallery.composeapp.generated.resources.login_subtitle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) onLoginSuccess()
    }

    LoginContent(
        state = state,
        onServerUrlChange = viewModel::updateServerUrl,
        onApiKeyChange = viewModel::updateApiKey,
        onLoginClick = viewModel::login
    )
}

@Composable
fun LoginContent(
    state: LoginState,
    onServerUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onLoginClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(Res.string.app_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(Dimens.largeSpacing))

        Text(
            text = stringResource(Res.string.login_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(Dimens.extraLargeSpacing))

        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text(stringResource(Res.string.login_server_url_label)) },
            placeholder = { Text(stringResource(Res.string.login_server_url_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(Dimens.mediumSpacing))

        OutlinedTextField(
            value = state.apiKey,
            onValueChange = onApiKeyChange,
            label = { Text(stringResource(Res.string.login_api_key_label)) },
            placeholder = { Text(stringResource(Res.string.login_api_key_placeholder)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        if (state.error != null) {
            Spacer(modifier = Modifier.height(Dimens.mediumSpacing))
            Text(
                text = state.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(Dimens.largeSpacing))

        Button(
            onClick = onLoginClick,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimens.iconSize),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(Res.string.login_connect))
            }
        }
    }
}

@Preview
@Composable
private fun LoginContentPreview() {
    LoginContent(
        state = LoginState(),
        onServerUrlChange = {},
        onApiKeyChange = {},
        onLoginClick = {}
    )
}

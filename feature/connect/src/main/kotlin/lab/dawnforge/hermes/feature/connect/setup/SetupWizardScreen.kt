package lab.dawnforge.hermes.feature.connect.setup

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.signin.StageState
import lab.dawnforge.hermes.core.data.signin.TestOutcome
import lab.dawnforge.hermes.core.data.signin.TestStage
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.connect.ButtonProgress
import lab.dawnforge.hermes.feature.connect.ErrorText
import lab.dawnforge.hermes.feature.connect.EventEffect
import lab.dawnforge.hermes.feature.connect.FormColumn
import lab.dawnforge.hermes.feature.connect.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupWizardScreen(
    viewModel: WizardViewModel,
    onClose: () -> Unit,
    onUse: (address: String, username: String, password: String) -> Unit = { _, _, _ -> },
    onSignIn: (address: String, username: String?) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    EventEffect(viewModel.events) { event ->
        when (event) {
            SetupEvent.Close -> onClose()

            SetupEvent.Reconnected -> {
                Toast.makeText(context, R.string.feature_connect_repair_reconnected, Toast.LENGTH_SHORT).show()
                onClose()
            }

            is SetupEvent.Use -> onUse(event.address, event.username, event.password)

            is SetupEvent.SignIn -> onSignIn(event.address, event.username)
        }
    }
    BackHandler(onBack = viewModel::back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(stepTitle(state.step, viewModel.mode))) },
                navigationIcon = {
                    IconButton(onClick = viewModel::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_connect_setup_back))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::close) {
                        Text(stringResource(R.string.feature_connect_setup_close))
                    }
                },
            )
        },
    ) { padding ->
        FormColumn(Modifier.padding(padding)) {
            when (state.step) {
                WizardStep.Dashboard -> DashboardStep(state, viewModel)
                WizardStep.Credentials -> CredentialsStep(state, viewModel)
                WizardStep.AccessMethod -> AccessMethodStep(state, viewModel)
                WizardStep.Lan, WizardStep.Tailscale, WizardStep.ReverseProxy -> MethodStep(state.step, viewModel)
                WizardStep.Details -> DetailsStep(state, viewModel)
                WizardStep.LoginCredentials -> LoginStep(state, viewModel)
                WizardStep.Test -> TestStep(state, viewModel)
                WizardStep.Review -> ReviewStep(state, viewModel)
                WizardStep.Tls -> ChecklistStep(Checklist.Tls, viewModel)
                WizardStep.Cloudflare -> ChecklistStep(Checklist.Cloudflare, viewModel)
            }
        }
    }
}

@StringRes
private fun stepTitle(step: WizardStep, mode: WizardMode): Int = when (step) {
    WizardStep.Details -> R.string.feature_connect_setup_details_title

    WizardStep.LoginCredentials -> R.string.feature_connect_setup_login_title

    WizardStep.Test -> R.string.feature_connect_setup_test_title

    WizardStep.Review -> R.string.feature_connect_setup_review_title

    WizardStep.Tls -> R.string.feature_connect_setup_tls_title

    WizardStep.Cloudflare -> R.string.feature_connect_setup_cloudflare_title

    else -> if (mode ==
        WizardMode.Repair
    ) {
        R.string.feature_connect_repair_title
    } else {
        R.string.feature_connect_setup_title
    }
}

// ---- Questions ------------------------------------------------------------------------------

@Composable
private fun DashboardStep(state: SetupUiState, viewModel: WizardViewModel) {
    Question(
        step = R.string.feature_connect_setup_step_1,
        question = R.string.feature_connect_setup_dashboard_question,
        explanation = R.string.feature_connect_setup_dashboard_explanation,
        answer = state.dashboardAnswer,
        onAnswer = viewModel::answerDashboard,
    )
    val prompt = when (state.dashboardAnswer) {
        SetupAnswer.No ->
            R.string.feature_connect_setup_prompt_dashboard_not_running_title to
                R.string.feature_connect_setup_prompt_dashboard_not_running

        SetupAnswer.Unknown ->
            R.string.feature_connect_setup_prompt_dashboard_unknown_title to
                R.string.feature_connect_setup_prompt_dashboard_unknown

        else -> null
    }
    prompt?.let { (title, text) ->
        AskHermes(title, text)
        Button(onClick = viewModel::dashboardReady, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.feature_connect_setup_dashboard_ready))
        }
    }
}

@Composable
private fun CredentialsStep(state: SetupUiState, viewModel: WizardViewModel) {
    Question(
        step = null,
        question = R.string.feature_connect_setup_credentials_question,
        explanation = R.string.feature_connect_setup_credentials_explanation,
        answer = state.credentialsAnswer,
        onAnswer = viewModel::answerCredentials,
    )
    val prompt = when (state.credentialsAnswer) {
        SetupAnswer.No ->
            R.string.feature_connect_setup_prompt_credentials_missing_title to
                R.string.feature_connect_setup_prompt_credentials_missing

        SetupAnswer.Unknown ->
            R.string.feature_connect_setup_prompt_credentials_unknown_title to
                R.string.feature_connect_setup_prompt_credentials_unknown

        else -> null
    }
    prompt?.let { (title, text) ->
        AskHermes(title, text)
        Button(onClick = viewModel::credentialsReady, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.feature_connect_setup_credentials_ready))
        }
    }
}

@Composable
private fun Question(
    @StringRes step: Int?,
    @StringRes question: Int,
    @StringRes explanation: Int,
    answer: SetupAnswer?,
    onAnswer: (SetupAnswer) -> Unit,
) {
    step?.let {
        Text(stringResource(it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Text(stringResource(question), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(explanation), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column {
        listOf(
            SetupAnswer.Yes to R.string.feature_connect_setup_yes,
            SetupAnswer.No to R.string.feature_connect_setup_no,
            SetupAnswer.Unknown to R.string.feature_connect_setup_unknown,
        ).forEach { (value, label) ->
            Choice(selected = answer == value, label = stringResource(label), onClick = { onAnswer(value) })
        }
    }
}

@Composable
private fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

// ---- How the phone reaches Hermes ----------------------------------------------------------

@Composable
private fun AccessMethodStep(state: SetupUiState, viewModel: WizardViewModel) {
    Text(
        stringResource(R.string.feature_connect_setup_step_2),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(stringResource(R.string.feature_connect_setup_access_title), style = MaterialTheme.typography.headlineSmall)
    Muted(stringResource(R.string.feature_connect_setup_access_detail))
    if (state.draft.existingAddress.isNotBlank()) {
        MethodCard(
            title = stringResource(R.string.feature_connect_setup_use_existing),
            detail = state.draft.existingAddress,
            onClick = viewModel::useExistingAddress,
        )
    }
    MethodCard(
        title = stringResource(R.string.feature_connect_setup_method_lan),
        detail = stringResource(R.string.feature_connect_setup_method_lan_detail),
        onClick = { viewModel.chooseMethod(AccessMethod.Lan) },
    )
    MethodCard(
        title = stringResource(R.string.feature_connect_setup_method_tailscale),
        detail = stringResource(R.string.feature_connect_setup_method_tailscale_detail),
        badge = stringResource(R.string.feature_connect_setup_method_tailscale_badge),
        onClick = { viewModel.chooseMethod(AccessMethod.Tailscale) },
    )
    MethodCard(
        title = stringResource(R.string.feature_connect_setup_method_proxy),
        detail = stringResource(R.string.feature_connect_setup_method_proxy_detail),
        onClick = { viewModel.chooseMethod(AccessMethod.ReverseProxy) },
    )
    TextButton(onClick = viewModel::toggleNotSure) { Text(stringResource(R.string.feature_connect_setup_not_sure)) }
    if (state.notSureShown) {
        Bullets(
            listOf(
                R.string.feature_connect_setup_not_sure_lan,
                R.string.feature_connect_setup_not_sure_tailscale,
                R.string.feature_connect_setup_not_sure_proxy,
            ),
        )
    }
}

@Composable
private fun MethodCard(title: String, detail: String, onClick: () -> Unit, badge: String? = null) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            badge?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Muted(detail, MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MethodStep(step: WizardStep, viewModel: WizardViewModel) {
    when (step) {
        WizardStep.Lan -> {
            Guide(
                title = R.string.feature_connect_setup_lan_title,
                intro = R.string.feature_connect_setup_lan_intro,
                checks = listOf(
                    R.string.feature_connect_setup_lan_check_1,
                    R.string.feature_connect_setup_lan_check_2,
                    R.string.feature_connect_setup_lan_check_3,
                    R.string.feature_connect_setup_lan_check_4,
                    R.string.feature_connect_setup_lan_check_5,
                ),
                numbered = false,
            )
            AskHermes(R.string.feature_connect_setup_prompt_lan_title, R.string.feature_connect_setup_prompt_lan)
        }

        WizardStep.Tailscale -> {
            Guide(
                title = R.string.feature_connect_setup_tailscale_title,
                intro = R.string.feature_connect_setup_tailscale_intro,
                checks = listOf(
                    R.string.feature_connect_setup_tailscale_check_1,
                    R.string.feature_connect_setup_tailscale_check_2,
                    R.string.feature_connect_setup_tailscale_check_3,
                    R.string.feature_connect_setup_tailscale_check_4,
                    R.string.feature_connect_setup_tailscale_check_5,
                    R.string.feature_connect_setup_tailscale_check_6,
                ),
                numbered = true,
            )
            Note(R.string.feature_connect_setup_tailscale_note)
            AskHermes(
                R.string.feature_connect_setup_prompt_tailscale_title,
                R.string.feature_connect_setup_prompt_tailscale,
            )
        }

        else -> {
            Guide(
                title = R.string.feature_connect_setup_proxy_title,
                intro = R.string.feature_connect_setup_proxy_intro,
                checks = listOf(
                    R.string.feature_connect_setup_proxy_check_1,
                    R.string.feature_connect_setup_proxy_check_2,
                    R.string.feature_connect_setup_proxy_check_3,
                    R.string.feature_connect_setup_proxy_check_4,
                ),
                numbered = false,
                lead = R.string.feature_connect_setup_proxy_need,
            )
            Note(R.string.feature_connect_setup_proxy_note)
            AskHermes(R.string.feature_connect_setup_prompt_proxy_title, R.string.feature_connect_setup_prompt_proxy)
        }
    }
    Button(onClick = viewModel::detailsReady, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.feature_connect_setup_details_ready))
    }
}

@Composable
private fun Guide(
    @StringRes title: Int,
    @StringRes intro: Int,
    checks: List<Int>,
    numbered: Boolean,
    @StringRes lead: Int? = null,
) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
    lead?.let { Text(stringResource(it), style = MaterialTheme.typography.titleSmall) }
    if (numbered) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            checks.forEachIndexed { index, check -> Text("${index + 1}. ${stringResource(check)}") }
        }
    } else {
        Bullets(checks)
    }
}

@Composable
private fun Bullets(items: List<Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { Text("• ${stringResource(it)}") }
    }
}

@Composable
private fun Note(@StringRes text: Int) {
    Muted(stringResource(text), MaterialTheme.typography.bodySmall)
}

@Composable
private fun Muted(text: String, style: TextStyle = LocalTextStyle.current) {
    Text(text, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A prompt to copy and send to Hermes, which keeps dashboard sign-in on. */
@Composable
private fun AskHermes(@StringRes title: Int, @StringRes text: Int) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val prompt = stringResource(text)
    var copied by remember(text) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(prompt, style = MaterialTheme.typography.bodyMedium) }
            TextButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("prompt", prompt)))
                        copied = true
                    }
                },
            ) {
                val label = if (copied) {
                    R.string.feature_connect_setup_prompt_copied
                } else {
                    R.string.feature_connect_setup_prompt_copy
                }
                Text(stringResource(label))
            }
        }
    }
}

// ---- Details and sign-in -------------------------------------------------------------------

@Composable
private fun DetailsStep(state: SetupUiState, viewModel: WizardViewModel) {
    val draft = state.draft
    when {
        draft.useExisting -> Field(
            value = draft.existingAddress,
            label = R.string.feature_connect_setup_existing_label,
            keyboard = KeyboardType.Uri,
            onChange = { value -> viewModel.editDraft { it.copy(existingAddress = value) } },
        )

        draft.method == AccessMethod.Lan -> {
            Field(draft.lanHost, R.string.feature_connect_setup_lan_host, KeyboardType.Uri) { value ->
                viewModel.editDraft { it.copy(lanHost = value) }
            }
            Field(draft.lanPort, R.string.feature_connect_setup_port, KeyboardType.Number) { value ->
                viewModel.editDraft { it.copy(lanPort = value) }
            }
        }

        draft.method == AccessMethod.Tailscale -> {
            Field(draft.tailscaleHost, R.string.feature_connect_setup_tailscale_host, KeyboardType.Uri) { value ->
                viewModel.editDraft { it.copy(tailscaleHost = value) }
            }
            Field(draft.tailscalePort, R.string.feature_connect_setup_port_optional, KeyboardType.Number) { value ->
                viewModel.editDraft { it.copy(tailscalePort = value) }
            }
            if (SetupAddressBuilder.isServeHostname(draft.tailscaleHost)) {
                Note(R.string.feature_connect_setup_scheme_https_auto)
            } else {
                Text(
                    stringResource(R.string.feature_connect_setup_scheme_choose),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SetupScheme.entries.forEach { scheme ->
                        Row(
                            Modifier.selectable(
                                selected = draft.tailscaleScheme == scheme,
                                role = Role.RadioButton,
                                onClick = { viewModel.editDraft { it.copy(tailscaleScheme = scheme) } },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = draft.tailscaleScheme == scheme, onClick = null)
                            Text(scheme.value.uppercase(), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }

        else -> Field(draft.proxyAddress, R.string.feature_connect_setup_proxy_address, KeyboardType.Uri) { value ->
            viewModel.editDraft { it.copy(proxyAddress = value) }
        }
    }
    (SetupAddressBuilder.build(draft) as? SetupAddress.Built)?.let {
        Text(
            stringResource(R.string.feature_connect_setup_preview, it.address),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.detailsProblem?.let { ErrorText(problemText(it)) }
    Button(onClick = viewModel::submitDetails, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.feature_connect_setup_continue))
    }
}

@Composable
private fun problemText(problem: SetupAddressProblem): String = when (problem) {
    SetupAddressProblem.MethodRequired -> stringResource(R.string.feature_connect_setup_problem_method)
    SetupAddressProblem.InvalidHost -> stringResource(R.string.feature_connect_setup_problem_host)
    SetupAddressProblem.InvalidPort -> stringResource(R.string.feature_connect_setup_problem_port)
    SetupAddressProblem.HttpsRequired -> stringResource(R.string.feature_connect_setup_problem_https)
    SetupAddressProblem.SchemeRequired -> stringResource(R.string.feature_connect_setup_problem_scheme)
    is SetupAddressProblem.Policy -> failureText(ConnectionFailure.InvalidAddress(problem.problem))
}

@Composable
private fun LoginStep(state: SetupUiState, viewModel: WizardViewModel) {
    Text(stringResource(R.string.feature_connect_setup_login_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (viewModel.mode != WizardMode.Setup) Note(R.string.feature_connect_repair_login_optional)
    Field(state.draft.username, R.string.feature_connect_setup_username, KeyboardType.Text) { value ->
        viewModel.editDraft { it.copy(username = value) }
    }
    OutlinedTextField(
        value = state.draft.password,
        onValueChange = { value -> viewModel.editDraft { it.copy(password = value) } },
        label = { Text(stringResource(R.string.feature_connect_setup_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.credentialsMissing) ErrorText(stringResource(R.string.feature_connect_setup_problem_credentials))
    Button(onClick = viewModel::submitCredentials, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.feature_connect_setup_continue))
    }
}

@Composable
private fun Field(value: String, @StringRes label: Int, keyboard: KeyboardType, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---- Test and review -----------------------------------------------------------------------

@Composable
private fun TestStep(state: SetupUiState, viewModel: WizardViewModel) {
    Text(stringResource(R.string.feature_connect_setup_test_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
    (SetupAddressBuilder.build(state.draft) as? SetupAddress.Built)?.let {
        Text(stringResource(R.string.feature_connect_setup_preview, it.address))
    }
    Stages(state)
    when (val outcome = state.outcome) {
        is TestOutcome.CredentialsRequired -> {
            Text(stringResource(R.string.feature_connect_setup_credentials_required))
            Button(onClick = { viewModel.goTo(WizardStep.LoginCredentials) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.feature_connect_setup_enter_credentials))
            }
        }

        is TestOutcome.Failed -> {
            Text(
                stringResource(
                    when (outcome.stage) {
                        TestStage.Server -> R.string.feature_connect_setup_failed_server
                        TestStage.Dashboard -> R.string.feature_connect_setup_failed_dashboard
                        TestStage.Authentication -> R.string.feature_connect_setup_failed_auth
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            ErrorText(failureText(outcome.failure))
            val credentials = outcome.stage == TestStage.Authentication
            OutlinedButton(
                onClick = { viewModel.goTo(if (credentials) WizardStep.LoginCredentials else WizardStep.Details) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                val label = if (credentials) {
                    R.string.feature_connect_setup_edit_credentials
                } else {
                    R.string.feature_connect_setup_edit_details
                }
                Text(stringResource(label))
            }
            // Hermes allows 10 password attempts a minute: trying again at once only fails again.
            if (outcome.failure !is ConnectionFailure.RateLimited) {
                Button(onClick = { viewModel.runTest() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feature_connect_setup_test_again))
                }
            }
        }

        else -> Button(
            onClick = { viewModel.runTest() },
            enabled = !state.testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.testing) ButtonProgress() else Text(stringResource(R.string.feature_connect_setup_test_run))
        }
    }
}

@StringRes
private fun stageLabel(stage: TestStage): Int = when (stage) {
    TestStage.Server -> R.string.feature_connect_setup_stage_server
    TestStage.Dashboard -> R.string.feature_connect_setup_stage_dashboard
    TestStage.Authentication -> R.string.feature_connect_setup_stage_auth
}

@Composable
private fun Stages(state: SetupUiState) {
    val test = state.test ?: return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TestStage.entries.forEach { stage ->
            val stageState = test.stage(stage)
            val text = stageText(stage, stageState, state.outcome)
            val status = stageStatus(stageState)
            // The marker shows the state; a screen reader hears it as a word.
            Row(
                Modifier.semantics(mergeDescendants = true) { contentDescription = "$text, $status" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StageMarker(stageState)
                Text(text, Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun stageText(stage: TestStage, stageState: StageState, outcome: TestOutcome?): String = when (stageState) {
    StageState.Running -> stringResource(
        when (stage) {
            TestStage.Server -> R.string.feature_connect_setup_stage_server_running
            TestStage.Dashboard -> R.string.feature_connect_setup_stage_dashboard_running
            TestStage.Authentication -> R.string.feature_connect_setup_stage_auth_running
        },
    )

    StageState.Passed -> stringResource(
        if (stage == TestStage.Authentication) R.string.feature_connect_setup_stage_auth_passed else stageLabel(stage),
    )

    StageState.Partial -> stringResource(
        when (outcome) {
            is TestOutcome.CredentialsRequired -> R.string.feature_connect_setup_stage_credentials
            is TestOutcome.SignInChoice -> R.string.feature_connect_setup_stage_choice
            else -> R.string.feature_connect_setup_stage_browser
        },
    )

    StageState.Pending, is StageState.Failed -> stringResource(stageLabel(stage))
}

@Composable
private fun stageStatus(stageState: StageState): String = stringResource(
    when (stageState) {
        StageState.Pending -> R.string.feature_connect_setup_stage_pending
        StageState.Running -> R.string.feature_connect_setup_stage_running
        StageState.Passed -> R.string.feature_connect_setup_stage_passed
        StageState.Partial -> R.string.feature_connect_setup_stage_partial
        is StageState.Failed -> R.string.feature_connect_setup_stage_failed
    },
)

@Composable
private fun StageMarker(stageState: StageState) {
    val size = Modifier.size(22.dp)
    when (stageState) {
        StageState.Pending -> Box(size.padding(2.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
        StageState.Running -> CircularProgressIndicator(size.padding(2.dp), strokeWidth = 2.dp)
        StageState.Passed -> Icon(Icons.Filled.CheckCircle, null, size, tint = MaterialTheme.colorScheme.primary)
        StageState.Partial -> Icon(Icons.Filled.Info, null, size, tint = MaterialTheme.colorScheme.primary)
        is StageState.Failed -> Icon(Icons.Filled.Warning, null, size, tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ReviewStep(state: SetupUiState, viewModel: WizardViewModel) {
    val outcome = state.outcome
    val repairing = viewModel.mode != WizardMode.Setup
    Text(
        stringResource(
            when {
                outcome is TestOutcome.BrowserSignIn && repairing -> R.string.feature_connect_repair_review_browser
                outcome is TestOutcome.BrowserSignIn -> R.string.feature_connect_setup_review_browser
                outcome is TestOutcome.SignInChoice && repairing -> R.string.feature_connect_repair_review_choice
                outcome is TestOutcome.SignInChoice -> R.string.feature_connect_setup_review_choice
                else -> R.string.feature_connect_setup_review_ready
            },
        ),
        style = MaterialTheme.typography.titleMedium,
    )
    val draft = state.draft
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Summary(R.string.feature_connect_setup_review_method, methodLabel(draft))
            Summary(R.string.feature_connect_setup_review_address, state.testedAddress.orEmpty())
            if (draft.username.isNotBlank()) {
                Summary(R.string.feature_connect_setup_review_username, draft.username.trim())
            }
            Summary(
                R.string.feature_connect_setup_review_password,
                stringResource(
                    when {
                        outcome is TestOutcome.BrowserSignIn -> R.string.feature_connect_setup_review_password_browser
                        draft.password.isNotEmpty() -> R.string.feature_connect_setup_review_password_entered
                        else -> R.string.feature_connect_setup_review_password_none
                    },
                ),
            )
        }
    }
    Stages(state)
    if (outcome is TestOutcome.SignInChoice) {
        OutlinedButton(onClick = { viewModel.goTo(WizardStep.LoginCredentials) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.feature_connect_setup_enter_credentials))
        }
    }
    if (viewModel is RepairWizardViewModel) {
        RepairFooter(state, viewModel)
    } else {
        Button(onClick = viewModel::useSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.feature_connect_setup_use))
        }
    }
}

/**
 * A repair's way back, as iOS's: Reconnect now with a tested password, Sign in to reconnect for a
 * browser sign-in, or test again once a reconnect has used the tested session.
 */
@Composable
private fun RepairFooter(state: SetupUiState, viewModel: RepairWizardViewModel) {
    state.reconnectFailure?.let { failure ->
        ErrorText(
            when (failure) {
                ReconnectFailure.AddressInUse -> stringResource(R.string.feature_connect_repair_address_in_use)

                is ReconnectFailure.NotConnected -> failure.failure?.let { failureText(it) }
                    ?: stringResource(R.string.feature_connect_repair_not_connected)
            },
        )
    }
    when {
        state.outcome is TestOutcome.Ready && state.reconnectFailure == null -> Button(
            onClick = viewModel::reconnect,
            enabled = !state.reconnecting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.reconnecting) {
                ButtonProgress()
                Text(stringResource(R.string.feature_connect_repair_reconnecting), Modifier.padding(start = 8.dp))
            } else {
                Text(
                    stringResource(
                        if (viewModel.mode == WizardMode.Edit) {
                            R.string.feature_connect_setup_use
                        } else {
                            R.string.feature_connect_repair_reconnect
                        },
                    ),
                )
            }
        }

        state.outcome is TestOutcome.BrowserSignIn || state.outcome is TestOutcome.SignInChoice -> Button(
            onClick = viewModel::signInToReconnect,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.feature_connect_repair_sign_in))
        }

        else -> Button(onClick = viewModel::testAgain, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.feature_connect_repair_test_again))
        }
    }
}

@Composable
private fun methodLabel(draft: SetupDraft): String = stringResource(
    when {
        draft.useExisting -> R.string.feature_connect_setup_review_existing
        draft.method == AccessMethod.Lan -> R.string.feature_connect_setup_method_lan
        draft.method == AccessMethod.Tailscale -> R.string.feature_connect_setup_method_tailscale
        else -> R.string.feature_connect_setup_method_proxy
    },
)

@Composable
private fun Summary(@StringRes label: Int, value: String) {
    Column {
        Muted(stringResource(label), MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

// ---- Troubleshooting -----------------------------------------------------------------------

private enum class Checklist(@StringRes val detail: Int, val checks: List<Int>) {
    Tls(
        R.string.feature_connect_setup_tls_detail,
        listOf(
            R.string.feature_connect_setup_tls_check_1,
            R.string.feature_connect_setup_tls_check_2,
            R.string.feature_connect_setup_tls_check_3,
        ),
    ),
    Cloudflare(
        R.string.feature_connect_setup_cloudflare_detail,
        listOf(
            R.string.feature_connect_setup_cloudflare_check_1,
            R.string.feature_connect_setup_cloudflare_check_2,
            R.string.feature_connect_setup_cloudflare_check_3,
        ),
    ),
}

@Composable
private fun ChecklistStep(checklist: Checklist, viewModel: WizardViewModel) {
    Text(stringResource(checklist.detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Bullets(checklist.checks)
    OutlinedButton(onClick = viewModel::setUpConnection, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.feature_connect_setup_set_up_connection))
    }
}

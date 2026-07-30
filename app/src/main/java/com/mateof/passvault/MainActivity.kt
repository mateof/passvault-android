package com.mateof.passvault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mateof.passvault.ui.theme.PassVaultTheme
import com.mateof.passvault.ui.wallet.WalletScreen
import com.mateof.passvault.ui.wallet.WalletViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge to edge before setContent, so the first frame is already laid out for it rather
        // than reflowing once the insets arrive.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PassVaultTheme {
                Wallet()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Wallet(viewModel: WalletViewModel = hiltViewModel()) {
    // collectAsStateWithLifecycle, not collectAsState: the database query should stop while the app
    // is in the background rather than keep the process awake for a screen nobody is looking at.
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Collapses as the list scrolls, giving the content the whole screen once the user is reading
    // rather than navigating.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { Text(stringResource(R.string.wallet_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        WalletScreen(
            state = state,
            onTicketClick = { },
            modifier = Modifier.padding(padding),
        )
    }
}

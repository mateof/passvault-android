package com.mateof.passvault.ui.wallet

/**
 * Sample data, debug builds only.
 *
 * Placeholder until Room lands: without it the wallet is empty, and an empty screen cannot show
 * whether the cards, the state indicators or the collapsing bar actually work. It lives in
 * `src/debug` rather than behind a flag so it cannot reach a release build at all.
 */
object SampleWallet {
    val state = WalletUiState(
        tickets = listOf(
            TicketRow(
                id = "1",
                eventName = "Festival do Norte 2026",
                label = "Grada A",
                seat = "Fila 14 · Asento B",
                state = TicketState.Held,
                paymentLabel = "45,00 €",
            ),
            TicketRow(
                id = "2",
                eventName = "Festival do Norte 2026",
                label = "Grada A",
                seat = "Fila 14 · Asento C",
                state = TicketState.Provisional,
                paymentLabel = null,
            ),
            TicketRow(
                id = "3",
                eventName = "Real Club Celta — Deportivo",
                label = "Marcador",
                seat = "Fila 7 · Asento 21",
                state = TicketState.Held,
                paymentLabel = "Sen pagar",
            ),
            TicketRow(
                id = "4",
                eventName = "Concerto de Nadal",
                label = "Entrada xeral",
                seat = null,
                state = TicketState.Free,
                paymentLabel = null,
            ),
            TicketRow(
                id = "5",
                eventName = "Concerto de Nadal",
                label = "Entrada xeral",
                seat = null,
                state = TicketState.Transferred,
                paymentLabel = "Cedida a Ana",
            ),
        ),
    )
}

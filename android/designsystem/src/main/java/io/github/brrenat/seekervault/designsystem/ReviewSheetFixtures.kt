package io.github.brrenat.seekervault.designsystem

/** Exact-copy fixtures for the five unrolled review-sheet design references. */
object ReviewSheetFixtures {
    private const val Wallet = "Bzy2Lson…2B16K54"
    private const val SandboxNotice =
        "Sandbox. Everything above is real — the live market, the exact transaction and this " +
            "phone’s reading of it. The last step is not: no funds will move, nothing is signed " +
            "and nothing is sent."
    private const val WarningConfirmation = "I have read the warning and want to approve anyway"

    val Transfer =
        ReviewSheetState(
            title = "Transfer",
            headline = "5 SOL",
            subline = "to FyfWsSPWUHbFWA5iwtgujuHbmaJxyCvrYSxLobvYSpEA",
            sublineStyle = ReviewSheetSublineStyle.Mono,
            headerChips =
                listOf(
                    ReviewSheetHeaderChip.Environment(EnvChipEnvironment.Production),
                    ReviewSheetHeaderChip.Network(NetworkChipNetwork.Devnet),
                ),
            verdict =
                ReviewSheetVerdict(
                    warnings =
                        listOf(
                            ReviewSheetWarning(
                                message =
                                    "Over the global daily limit of 10 SOL. 6 SOL has already " +
                                        "moved across all connections today, so this would reach " +
                                        "11.",
                                sourceLabel = "Global rule",
                                kind = VerdictWarningKind.DailyLimit,
                            )
                        )
                ),
            dailySpend =
                ReviewSheetDailySpend(
                    title = "Daily spend, if you approve",
                    rows =
                        listOf(
                            ReviewSheetDailySpendRow(
                                headline = "6 of 10 SOL, this takes it to 11",
                                supportingText = "Across all connections and feeds",
                                state = DailyLimitRowState.Over,
                                scope = DailyLimitRowScope.Global,
                            ),
                            ReviewSheetDailySpendRow(
                                headline = "1.5 of 8 SOL, this takes it to 6.5",
                                supportingText = "Through studio-mac only",
                                state = DailyLimitRowState.Within,
                                scope = DailyLimitRowScope.Connection,
                            ),
                        ),
                ),
            infoBlocks =
                listOf(
                    ReviewSheetInfoBlock(
                        "Checked on this phone: the transaction does exactly what this request says."
                    )
                ),
            factRows =
                listOf(
                    ReviewSheetFactRow("From", "studio-mac"),
                    ReviewSheetFactRow("Wallet", Wallet, FactRowValueStyle.Mono),
                    ReviewSheetFactRow("Network", "Solana devnet"),
                    ReviewSheetFactRow("Asset", "SOL"),
                ),
            note =
                ReviewSheetNote(
                    label = "The agent’s note · not verified",
                    body = "Rebalancing the treasury wallet.",
                ),
            expiry = "in 23 hours",
            confirmationCheckbox = WarningConfirmation,
            primaryAction = ReviewSheetAction("Approve and send"),
            secondaryAction = ReviewSheetAction("Reject"),
            footerCaption = "Rejecting tells studio-mac you said no.",
        )

    val Swap =
        ReviewSheetState(
            title = "Swap",
            headline = "SOL → USDC",
            subline =
                "CopyTrading demo proposes the route and a slippage cap. The amount is yours.",
            headerChips =
                listOf(
                    ReviewSheetHeaderChip.Signal,
                    ReviewSheetHeaderChip.Feed("CopyTrading demo"),
                    ReviewSheetHeaderChip.Environment(
                        EnvChipEnvironment.Sandbox,
                        EnvChipVerbosity.Short,
                    ),
                    ReviewSheetHeaderChip.Network(NetworkChipNetwork.Devnet),
                ),
            sandboxNotice = SandboxNotice,
            yourPart =
                ReviewSheetYourPart(
                    kind = OwnerInputCardKind.Swap,
                    state = OwnerInputCardState.Unchosen,
                ),
            verdict =
                ReviewSheetVerdict(
                    warnings =
                        listOf(
                            ReviewSheetWarning(
                                message = "Jupiter Aggregator is not a listed program.",
                                sourceLabel = "Global rule",
                            )
                        )
                ),
            infoBlocks =
                listOf(
                    ReviewSheetInfoBlock(
                        title = "The whole operation, quoted here",
                        body =
                            "Choose an amount and this phone fetches a quote, then shows the " +
                                "expected output, the minimum you would receive and the slippage " +
                                "it was checked against.",
                    ),
                    ReviewSheetInfoBlock(
                        "The signal carries the route and the slippage cap, not an amount. Choose " +
                            "one and this phone will fetch a quote and check the whole operation " +
                            "against it."
                    ),
                ),
            factRows =
                listOf(
                    ReviewSheetFactRow("From", "CopyTrading demo"),
                    ReviewSheetFactRow("Wallet", Wallet, FactRowValueStyle.Mono),
                    ReviewSheetFactRow("Network", "Solana devnet"),
                    ReviewSheetFactRow("Asset", "SOL"),
                ),
            note =
                ReviewSheetNote(
                    label = "The publisher’s note · not verified",
                    body = "hermes stream check",
                ),
            expiry = "in 2 hours",
            confirmationCheckbox = WarningConfirmation,
            primaryAction = ReviewSheetAction("Simulate the swap", enabled = false),
            secondaryAction = ReviewSheetAction("Dismiss"),
            footerCaption =
                "Dismissing keeps the decision on this phone. CopyTrading demo is never told " +
                    "either way.",
        )

    val Prediction =
        ReviewSheetState(
            title = "Prediction",
            headline = "Bitcoin under $68,000",
            subline =
                "Jupiter Prediction, market POLY-4470843, closing 6:00 PM. The side and the stake " +
                    "are yours.",
            headerChips =
                listOf(
                    ReviewSheetHeaderChip.Signal,
                    ReviewSheetHeaderChip.Feed("Jupiter Prediction demo"),
                    ReviewSheetHeaderChip.Environment(
                        EnvChipEnvironment.Sandbox,
                        EnvChipVerbosity.Short,
                    ),
                    ReviewSheetHeaderChip.Network(NetworkChipNetwork.Devnet),
                ),
            sandboxNotice = SandboxNotice,
            yourPart =
                ReviewSheetYourPart(
                    kind = OwnerInputCardKind.Prediction,
                    state = OwnerInputCardState.Unchosen,
                ),
            verdict =
                ReviewSheetVerdict(
                    warnings =
                        listOf(
                            ReviewSheetWarning(
                                message = "USDC on devnet is not a listed asset.",
                                sourceLabel = "Global rule",
                            )
                        )
                ),
            infoBlocks =
                listOf(
                    ReviewSheetInfoBlock(
                        "The market, its prices and its rules were read on this phone. Nothing " +
                            "here was taken from the publisher on trust, and neither the side nor " +
                            "the stake comes from the signal."
                    )
                ),
            factRows =
                listOf(
                    ReviewSheetFactRow("From", "Jupiter Prediction demo"),
                    ReviewSheetFactRow("Wallet", Wallet, FactRowValueStyle.Mono),
                    ReviewSheetFactRow("Network", "Solana devnet"),
                    ReviewSheetFactRow("Asset", "USDC"),
                ),
            note =
                ReviewSheetNote(
                    label = "The publisher’s note · not verified",
                    body =
                        "Listed on Jupiter Prediction as “Bitcoin price on September 18 — under " +
                            "$68,000”, closing 6:00 PM.",
                ),
            expiry = "in 2 hours",
            confirmationCheckbox = WarningConfirmation,
            primaryAction = ReviewSheetAction("Simulate the stake", enabled = false),
            secondaryAction = ReviewSheetAction("Dismiss"),
            footerCaption =
                "Dismissing keeps the decision on this phone. Jupiter Prediction demo is never " +
                    "told either way.",
        )

    val Signature =
        ReviewSheetState(
            title = "Signature",
            headline = "74 bytes",
            subline = "hermes-agent login 2026-09-12T21:05Z nonce=8f21c4",
            sublineStyle = ReviewSheetSublineStyle.Mono,
            headerChips =
                listOf(
                    ReviewSheetHeaderChip.Environment(EnvChipEnvironment.Production),
                    ReviewSheetHeaderChip.Network(NetworkChipNetwork.Mainnet),
                ),
            verdict = ReviewSheetVerdict(),
            infoBlocks =
                listOf(
                    ReviewSheetInfoBlock(
                        "Signs this message and returns the signature to hermes-box. No " +
                            "transaction is submitted by this action."
                    )
                ),
            factRows =
                listOf(
                    ReviewSheetFactRow("From", "hermes-box"),
                    ReviewSheetFactRow("Wallet", Wallet, FactRowValueStyle.Mono),
                    ReviewSheetFactRow("Network", "Solana mainnet"),
                ),
            note =
                ReviewSheetNote(
                    label = "The agent’s note · not verified",
                    body = "Proving wallet ownership to the indexer.",
                ),
            expiry = "in 23 hours",
            primaryAction = ReviewSheetAction("Approve and sign"),
            secondaryAction = ReviewSheetAction("Reject"),
            footerCaption = "Rejecting tells hermes-box you said no.",
        )

    val Acknowledge =
        ReviewSheetState(
            title = "Acknowledge",
            headline = "Still here?",
            subline = "studio-mac asks",
            headerChips = listOf(ReviewSheetHeaderChip.Environment(EnvChipEnvironment.Production)),
            verdict = ReviewSheetVerdict(),
            infoBlocks =
                listOf(
                    ReviewSheetInfoBlock(
                        "Nothing is signed and no funds move. Your answer is all the agent gets."
                    )
                ),
            factRows = listOf(ReviewSheetFactRow("From", "studio-mac")),
            note =
                ReviewSheetNote(
                    label = "The agent’s note · not verified",
                    body = "Checking the phone is awake before the next step.",
                ),
            expiry = "in 6 hours",
            primaryAction = ReviewSheetAction("Acknowledge"),
            secondaryAction = ReviewSheetAction("Reject"),
            footerCaption = "Rejecting tells studio-mac you said no.",
        )
}

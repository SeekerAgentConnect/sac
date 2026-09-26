package io.github.brrenat.seekervault.designsystem

/*
 * Exact-copy fixtures for the ten variant screens in docs/design/history-details/screens/. The
 * first viewport of each is copied from its reference; what lies below it follows TASK.md's atoms.
 */

private const val Recipient = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
private const val SignatureA =
    "UjhBd35BTTjj5qVoF3R5B9bBDmX7fq1P3mTd39jh9P3uFhHR5Mbf9ujVKK9dumuBZb1f5Pqw1PuHfbMwoRHmbFB1"
private const val SignatureB =
    "g1KomsQ8f3zq1S8n7R5KxvC5aYy3jHh2m4GgqK9tWc2bVvQe8LxZ7pD6uT1rN3sA5wE4iJ9oY2kX6mB8cF0yZ5My3"
private const val SignatureC =
    "ZH1VPqa7Nn4b2Rr8Tt6Yy3Uu9Ii5Oo1Pp7Aa3Ss5Dd8Ff2Gg4Hh6Jj9Kk1Ll3Zz5Xx7Cc2Vv4Bb6Nn8Mmshfysm"
private const val SignatureD =
    "AMu7jV3k9Lq2Wn5Xr8Tz1Yb4Hc6Pd7Fs2Gm5Jv8Kx1Nq3Rt6Uw9Ya2Ze4Bf7Dh1Ej5Gk8Hl3Ip6Mo9Qs2my37Pj"
private const val SignatureE =
    "5Xk2Pq9Rt3Uw6Ya1Zb4Hc7Jd2Ke5Lf8Mg3Nh6Pi9Qj2Rk5Sm8Tn1Up4Vq7Wr3Xs6Yt9Zu2Av5Bw8Cx1Dy4Ez7Fa"

private val ProductionFootnote =
    "Recorded on this phone when it happened. Current rules and market prices don't change " +
        "what's shown here."

private fun mainnetTransaction(
    label: String,
    signature: String,
    status: HistoryDetailTransactionStatus,
) =
    HistoryDetailTransaction(
        label = label,
        signature = signature,
        status = status,
        explorerLabel = "View on explorer · Mainnet",
        explorerUrl = "https://explorer.solana.com/tx/$signature",
    )

internal object HistoryDetailFixtures {
    val longContent =
        HistoryDetailModel(
            header =
                HistoryDetailHeader(
                    origin = HistoryDetailOrigin.Signal,
                    sourceName = "Prediction Signals",
                    sourceColour = SourceColour.Teal,
                    title =
                        "Place 25 USDC on Yes: Will SOL close above \$250 on Friday, 31 October 2026?",
                    environment = HistoryDetailEnvironment.Production,
                    networkText = "Mainnet",
                ),
            status =
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Approved,
                    explanation =
                        "You approved on this phone. Seed Vault Wallet signed both transactions.",
                ),
            response =
                HistoryDetailResponse.Sent(
                    timestampText = "Sep 26, 9:00:41 PM",
                    rows =
                        listOf(
                            HistoryDetailRow("Decision", "Approved"),
                            HistoryDetailRow("Side you chose", "Yes"),
                            HistoryDetailRow("Stake you entered", "25 USDC"),
                            HistoryDetailRow("Highest price you accepted", "0.58 USDC a share"),
                        ),
                    message =
                        "Hold until the market closes unless the price falls below 0.30. Don't " +
                            "add to this position without asking me first.",
                ),
            delivery =
                HistoryDetailDelivery(
                    title = "Prediction Signals hasn't confirmed it received your response",
                    body =
                        "Your approval is stored on this phone and the trade went through. The " +
                            "last delivery attempt, at 9:02 PM, got no answer from the feed.",
                ),
            execution =
                HistoryDetailExecution(
                    state = HistoryDetailExecutionState.Confirmed,
                    title = "Confirmed on Mainnet",
                    body = "Finalized at 9:01:12 PM, 31 seconds after you approved.",
                    rows =
                        listOf(
                            HistoryDetailRow("Shares received", "43.1"),
                            HistoryDetailRow("Network fee", "0.00001 SOL"),
                        ),
                ),
            originalDescription =
                "Momentum on SOL into the monthly close. The feed suggests Yes at up to 0.60.",
            originalRows =
                listOf(
                    HistoryDetailRow(
                        "Market",
                        "Will SOL close above \$250 on Friday, 31 October 2026?",
                    ),
                    HistoryDetailRow("Suggested side", "Yes"),
                    HistoryDetailRow("Price when received", "0.56 USDC a share"),
                ),
            transactions =
                listOf(
                    mainnetTransaction(
                        "Create USDC share account",
                        SignatureB,
                        HistoryDetailTransactionStatus.Confirmed,
                    ),
                    mainnetTransaction(
                        "Place order",
                        SignatureC,
                        HistoryDetailTransactionStatus.Confirmed,
                    ),
                ),
            timeline =
                listOf(
                    HistoryDetailTimelineEntry(HistoryDetailEvent.Received, "Received", "8:58 PM"),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Approved,
                        "You approved",
                        "9:00 PM",
                    ),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Sent,
                        "Sent to the network",
                        "9:00 PM",
                    ),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Confirmed,
                        "Confirmed",
                        "9:01 PM",
                    ),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.DeliveryUnconfirmed,
                        "Delivery to feed unconfirmed",
                        "9:02 PM",
                    ),
                ),
            identifiers =
                listOf(
                    HistoryDetailRow("Signal ID", "sig_01J8ZF9A3B5C7D2EG4HJ"),
                    HistoryDetailRow("Market ID", "mkt_sol_250_20261031"),
                ),
            footnote = ProductionFootnote,
        )

    val approvedPending =
        HistoryDetailModel(
            header =
                HistoryDetailHeader(
                    origin = HistoryDetailOrigin.Signal,
                    sourceName = "Trader Signals",
                    sourceColour = SourceColour.Sand,
                    title = "Swap 25 USDC for SOL",
                    environment = HistoryDetailEnvironment.Production,
                    networkText = "Mainnet",
                ),
            status =
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Approved,
                    explanation = "You approved on this phone. The wallet sent it to the network.",
                ),
            response =
                HistoryDetailResponse.Sent(
                    timestampText = "Sep 26, 9:14:02 PM",
                    rows =
                        listOf(
                            HistoryDetailRow("Decision", "Approved"),
                            HistoryDetailRow("Amount you entered", "25 USDC"),
                            HistoryDetailRow("Most the price could move", "0.5%"),
                        ),
                ),
            execution =
                HistoryDetailExecution(
                    state = HistoryDetailExecutionState.Pending,
                    title = "Waiting for network confirmation",
                    body =
                        "Sent at 9:14:02 PM and not confirmed yet. Last checked 9:16 PM. " +
                            "Approving it doesn't mean it went through.",
                    rows = listOf(HistoryDetailRow("SOL received", "Known after confirmation")),
                ),
            originalDescription =
                "SOL broke out of its 4-hour range. The feed suggests 20–50 USDC.",
            originalRows =
                listOf(
                    HistoryDetailRow("You pay", "USDC"),
                    HistoryDetailRow("You receive", "SOL"),
                    HistoryDetailRow("Quote when received", "0.1641 SOL for 25 USDC"),
                    HistoryDetailRow("Least you'd accept", "0.1633 SOL"),
                ),
            transactions =
                listOf(
                    mainnetTransaction("Swap", SignatureD, HistoryDetailTransactionStatus.Pending)
                ),
            timeline =
                listOf(
                    HistoryDetailTimelineEntry(HistoryDetailEvent.Received, "Received", "9:12 PM"),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Approved,
                        "You approved",
                        "9:14 PM",
                    ),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Sent,
                        "Sent to the network",
                        "9:14 PM",
                    ),
                ),
            identifiers = listOf(HistoryDetailRow("Signal ID", "sig_01J8ZG2K4M6N8P0Q2R4S6T8V")),
            footnote = ProductionFootnote,
        )

    val approvedConfirmed =
        HistoryDetailModel(
            header =
                HistoryDetailHeader(
                    origin = HistoryDetailOrigin.Request,
                    sourceName = "Personal MCP",
                    sourceColour = SourceColour.Sky,
                    title = "Send 0.4 SOL",
                    environment = HistoryDetailEnvironment.Production,
                    networkText = "Mainnet",
                ),
            status =
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Approved,
                    explanation = "You approved on this phone. Seed Vault Wallet signed it.",
                ),
            response =
                HistoryDetailResponse.Sent(
                    timestampText = "Sep 26, 8:37:12 PM",
                    rows =
                        listOf(
                            HistoryDetailRow("Decision", "Approved"),
                            HistoryDetailRow("Delivered to", "Personal MCP, 8:37 PM"),
                        ),
                ),
            execution =
                HistoryDetailExecution(
                    state = HistoryDetailExecutionState.Confirmed,
                    title = "Confirmed on Mainnet",
                    body = "Finalized at 8:37:31 PM, 19 seconds after you approved.",
                    rows = listOf(HistoryDetailRow("Network fee", "0.000005 SOL")),
                ),
            originalDescription = "Pay Dana back for the concert tickets.",
            originalRows =
                listOf(
                    HistoryDetailRow("Amount", "0.4 SOL"),
                    HistoryDetailRow("Token", "SOL"),
                    HistoryDetailRow("Recipient", Recipient, HistoryDetailRowLayout.Block),
                ),
            transactions =
                listOf(
                    mainnetTransaction(
                        "Transfer",
                        SignatureA,
                        HistoryDetailTransactionStatus.Confirmed,
                    )
                ),
            timeline =
                listOf(
                    HistoryDetailTimelineEntry(HistoryDetailEvent.Received, "Received", "8:36 PM"),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Approved,
                        "You approved",
                        "8:37 PM",
                    ),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Sent,
                        "Sent to the network",
                        "8:37 PM",
                    ),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Delivered,
                        "Delivered to Personal MCP",
                        "8:37 PM",
                    ),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Confirmed,
                        "Confirmed",
                        "8:37 PM",
                    ),
                ),
            identifiers =
                listOf(
                    HistoryDetailRow("Request ID", "req_01J8ZF9A3B5C7D2EG4HJ"),
                    HistoryDetailRow("Signature", SignatureA),
                ),
            footnote = ProductionFootnote,
        )

    val approvedFailed =
        HistoryDetailModel(
            header =
                HistoryDetailHeader(
                    origin = HistoryDetailOrigin.Request,
                    sourceName = "Personal MCP",
                    sourceColour = SourceColour.Sky,
                    title = "Send 1.2 SOL",
                    environment = HistoryDetailEnvironment.Production,
                    networkText = "Mainnet",
                ),
            status =
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Approved,
                    explanation =
                        "You approved on this phone. The network rejected the transaction.",
                ),
            response =
                HistoryDetailResponse.Sent(
                    timestampText = "Sep 26, 8:52:31 PM",
                    rows =
                        listOf(
                            HistoryDetailRow("Decision", "Approved"),
                            HistoryDetailRow("Delivered to", "Personal MCP, 8:52 PM"),
                        ),
                ),
            execution =
                HistoryDetailExecution(
                    state = HistoryDetailExecutionState.Failed,
                    title = "Failed on the network",
                    body = "Rejected at 8:52:40 PM. No SOL left the wallet except the network fee.",
                    failureReason =
                        "The wallet didn't hold enough SOL for the amount plus the fee: 1.2031 SOL " +
                            "available, 1.2050 SOL needed.",
                ),
            originalDescription = "Monthly transfer to savings.",
            originalRows =
                listOf(
                    HistoryDetailRow("Amount", "1.2 SOL"),
                    HistoryDetailRow("Token", "SOL"),
                    HistoryDetailRow("Recipient", Recipient, HistoryDetailRowLayout.Block),
                ),
            transactions =
                listOf(
                    mainnetTransaction(
                        "Transfer",
                        SignatureE,
                        HistoryDetailTransactionStatus.Failed,
                    )
                ),
            timeline =
                listOf(
                    HistoryDetailTimelineEntry(HistoryDetailEvent.Received, "Received", "8:51 PM"),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Approved,
                        "You approved",
                        "8:52 PM",
                    ),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Sent,
                        "Sent to the network",
                        "8:52 PM",
                    ),
                    HistoryDetailTimelineEntry(HistoryDetailEvent.Failed, "Failed", "8:52 PM"),
                ),
            identifiers =
                listOf(
                    HistoryDetailRow("Request ID", "req_01J8ZFB7C9D1E3F5G7H9J1K3"),
                    HistoryDetailRow("Signature", SignatureE),
                    HistoryDetailRow(
                        "Error",
                        "InstructionError(0, Custom(1)): insufficient lamports 1203100000, need 1205000000",
                    ),
                ),
            footnote = ProductionFootnote,
        )

    val declined =
        HistoryDetailModel(
            header =
                HistoryDetailHeader(
                    origin = HistoryDetailOrigin.Request,
                    sourceName = "Staking MCP",
                    sourceColour = SourceColour.Violet,
                    title = "Stake 10 SOL",
                    environment = HistoryDetailEnvironment.Production,
                    networkText = "Mainnet",
                ),
            status =
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Declined,
                    explanation = "You declined on this phone. Staking MCP was told.",
                ),
            response =
                HistoryDetailResponse.Sent(
                    timestampText = "Sep 26, 7:52:09 PM",
                    rows =
                        listOf(
                            HistoryDetailRow("Decision", "Declined"),
                            HistoryDetailRow("Delivered to", "Staking MCP, 7:52 PM"),
                        ),
                    message = "Too much at once. Try 2 SOL this week.",
                ),
            originalDescription =
                "Stake with the Helius validator. About 6.8% a year, as estimated when the request " +
                    "was sent.",
            originalRows =
                listOf(
                    HistoryDetailRow("Amount", "10 SOL"),
                    HistoryDetailRow("Validator", "Helius"),
                    HistoryDetailRow(
                        "Vote account",
                        "he1iusunGwqrNtafDtLdhsUQDFvo13z9sUa36PauBtk",
                        HistoryDetailRowLayout.Block,
                    ),
                    HistoryDetailRow("Unlocking", "About 2 days after you unstake"),
                ),
            timeline =
                listOf(
                    HistoryDetailTimelineEntry(HistoryDetailEvent.Received, "Received", "7:50 PM"),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Declined,
                        "You declined",
                        "7:52 PM",
                    ),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Delivered,
                        "Delivered to Staking MCP",
                        "7:52 PM",
                    ),
                ),
            identifiers = listOf(HistoryDetailRow("Request ID", "req_01J8ZE4Q6R8S0T2U4V6W8X0Y")),
            footnote = ProductionFootnote,
        )

    val expired =
        HistoryDetailModel(
            header =
                HistoryDetailHeader(
                    origin = HistoryDetailOrigin.Request,
                    sourceName = "Personal MCP",
                    sourceColour = SourceColour.Sky,
                    title = "Send 0.25 SOL",
                    environment = HistoryDetailEnvironment.Production,
                    networkText = "Devnet",
                ),
            status =
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Expired,
                    explanation = "It expired at 7:45 PM before you answered.",
                ),
            response =
                HistoryDetailResponse.None(
                    "You didn't answer before it expired. This isn't recorded as a decline."
                ),
            originalDescription = "Test payment to the devnet faucet wallet.",
            originalRows =
                listOf(
                    HistoryDetailRow("Amount", "0.25 SOL"),
                    HistoryDetailRow("Token", "SOL"),
                    HistoryDetailRow("Recipient", Recipient, HistoryDetailRowLayout.Block),
                    HistoryDetailRow("Expiry", "15 minutes after it arrived"),
                ),
            timeline =
                listOf(
                    HistoryDetailTimelineEntry(HistoryDetailEvent.Received, "Received", "7:30 PM"),
                    HistoryDetailTimelineEntry(HistoryDetailEvent.Expired, "Expired", "7:45 PM"),
                ),
            identifiers = listOf(HistoryDetailRow("Request ID", "req_01J8ZD1A3B5C7D9E1F3G5H7J")),
            footnote = ProductionFootnote,
        )

    val cancelled =
        HistoryDetailModel(
            header =
                HistoryDetailHeader(
                    origin = HistoryDetailOrigin.Request,
                    sourceName = "Personal MCP",
                    sourceColour = SourceColour.Sky,
                    title = "Send 2 SOL",
                    environment = HistoryDetailEnvironment.Production,
                    networkText = "Mainnet",
                ),
            status =
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Cancelled,
                    explanation = "Personal MCP withdrew it at 7:48 PM, before you answered.",
                    reasonLabel = "Reason given by Personal MCP",
                    reason = "Replaced by a newer request for 1.5 SOL.",
                ),
            response =
                HistoryDetailResponse.None(
                    "The server cancelled the request before you answered. Nothing was signed."
                ),
            originalDescription = "Rent share for October.",
            originalRows =
                listOf(
                    HistoryDetailRow("Amount", "2 SOL"),
                    HistoryDetailRow("Token", "SOL"),
                    HistoryDetailRow("Recipient", Recipient, HistoryDetailRowLayout.Block),
                ),
            timeline =
                listOf(
                    HistoryDetailTimelineEntry(HistoryDetailEvent.Received, "Received", "7:41 PM"),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Cancelled,
                        "Cancelled by Personal MCP",
                        "7:48 PM",
                    ),
                ),
            identifiers = listOf(HistoryDetailRow("Request ID", "req_01J8ZD8K0L2M4N6P8Q0R2S4T")),
            footnote = ProductionFootnote,
        )

    val dismissedSignal =
        HistoryDetailModel(
            header =
                HistoryDetailHeader(
                    origin = HistoryDetailOrigin.Signal,
                    sourceName = "Prediction Signals",
                    sourceColour = SourceColour.Teal,
                    title = "ETH above \$4,000 by Oct 31",
                    environment = HistoryDetailEnvironment.Production,
                    networkText = "Mainnet",
                ),
            status =
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Dismissed,
                    explanation = "You dismissed it on this phone.",
                ),
            response =
                HistoryDetailResponse.Sent(
                    timestampText = "Sep 26, 8:02:44 PM",
                    rows = listOf(HistoryDetailRow("Decision", "Dismissed")),
                    note = "Feeds aren't told when you dismiss a signal.",
                ),
            originalDescription = "Market odds moved to 62% after a week of ETF inflows.",
            originalRows =
                listOf(
                    HistoryDetailRow("Market", "Will ETH close above \$4,000 on Oct 31?"),
                    HistoryDetailRow("Suggested side", "Yes"),
                    HistoryDetailRow("Price when received", "0.62 USDC a share"),
                ),
            timeline =
                listOf(
                    HistoryDetailTimelineEntry(HistoryDetailEvent.Received, "Received", "8:00 PM"),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Dismissed,
                        "You dismissed",
                        "8:02 PM",
                    ),
                ),
            identifiers = listOf(HistoryDetailRow("Signal ID", "sig_01J8ZG4H6K8M2P9RT1VX")),
            footnote = ProductionFootnote,
        )

    val signedMessage =
        HistoryDetailModel(
            header =
                HistoryDetailHeader(
                    origin = HistoryDetailOrigin.Request,
                    sourceName = "Staking MCP",
                    sourceColour = SourceColour.Violet,
                    title = "Sign a message",
                    environment = HistoryDetailEnvironment.Production,
                    networkText = "Mainnet",
                ),
            status =
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Approved,
                    explanation = "You approved. Seed Vault Wallet signed the message.",
                ),
            response =
                HistoryDetailResponse.Sent(
                    timestampText = "Sep 26, 7:53:21 PM",
                    rows =
                        listOf(
                            HistoryDetailRow("Decision", "Approved"),
                            HistoryDetailRow("Delivered to", "Staking MCP, 7:53 PM"),
                        ),
                ),
            execution =
                HistoryDetailExecution(
                    state = HistoryDetailExecutionState.Signed,
                    title = "Signed · no transaction",
                    body =
                        "Signing a message doesn't touch the chain, so there is nothing to " +
                            "confirm and no funds moved.",
                ),
            originalDescription =
                "Prove you own this wallet to log in to the Staking MCP dashboard.",
            originalRows =
                listOf(
                    HistoryDetailRow(
                        "Message",
                        "staking-mcp.app wants you to sign in with your Solana account:\n" +
                            "bK5qBC6hj1onSwFyTHfBKRKWxWfQHx9Mw47gUhZXesKm\n\nNonce: 8f3a91c2\n" +
                            "Issued at: 2026-09-26T19:53:02Z",
                        HistoryDetailRowLayout.Block,
                    )
                ),
            timeline =
                listOf(
                    HistoryDetailTimelineEntry(HistoryDetailEvent.Received, "Received", "7:53 PM"),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Approved,
                        "You approved",
                        "7:53 PM",
                    ),
                    HistoryDetailTimelineEntry(HistoryDetailEvent.Signed, "Signed", "7:53 PM"),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Delivered,
                        "Delivered to Staking MCP",
                        "7:53 PM",
                    ),
                ),
            identifiers =
                listOf(
                    HistoryDetailRow("Request ID", "req_01J8ZE6Y8Z0A2B4C6D8E0F2G"),
                    HistoryDetailRow("Message signature", SignatureA),
                ),
            footnote = ProductionFootnote,
        )

    val sandbox =
        HistoryDetailModel(
            header =
                HistoryDetailHeader(
                    origin = HistoryDetailOrigin.Signal,
                    sourceName = "Trader Signals",
                    sourceColour = SourceColour.Sand,
                    title = "Swap 0.5 SOL for USDC",
                    environment = HistoryDetailEnvironment.Sandbox,
                    networkText = "Devnet",
                ),
            status =
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Approved,
                    explanation = "You approved the simulation on this phone. Nothing was signed.",
                ),
            response =
                HistoryDetailResponse.Sent(
                    timestampText = "Sep 26, 8:11:02 PM",
                    rows =
                        listOf(
                            HistoryDetailRow("Decision", "Approved (simulation)"),
                            HistoryDetailRow("Amount you entered", "0.5 SOL"),
                            HistoryDetailRow("Most the price could move", "0.5%"),
                        ),
                ),
            execution =
                HistoryDetailExecution(
                    state = HistoryDetailExecutionState.Simulated,
                    title = "Simulated · No funds moved",
                    body =
                        "Built and simulated against Devnet at 8:11:04 PM. It was never signed or " +
                            "sent.",
                    rows =
                        listOf(
                            HistoryDetailRow("Simulation", "Passed"),
                            HistoryDetailRow("Would have received", "71.84 USDC"),
                        ),
                ),
            originalDescription = "Mean-reversion signal on SOL/USDC.",
            originalRows =
                listOf(
                    HistoryDetailRow("You pay", "SOL"),
                    HistoryDetailRow("You receive", "USDC"),
                    HistoryDetailRow("Quote when received", "71.90 USDC for 0.5 SOL"),
                ),
            timeline =
                listOf(
                    HistoryDetailTimelineEntry(HistoryDetailEvent.Received, "Received", "8:10 PM"),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Approved,
                        "You approved",
                        "8:11 PM",
                    ),
                    HistoryDetailTimelineEntry(
                        HistoryDetailEvent.Simulated,
                        "Simulated",
                        "8:11 PM",
                    ),
                ),
            identifiers = listOf(HistoryDetailRow("Signal ID", "sig_01J8ZF2B4C6D8E0F2G4H6J8K")),
            footnote =
                "Recorded on this phone when it happened. A sandbox item was never signed or sent, " +
                    "so it has no transaction.",
        )
}

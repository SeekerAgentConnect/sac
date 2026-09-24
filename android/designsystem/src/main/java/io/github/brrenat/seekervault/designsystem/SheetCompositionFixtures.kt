package io.github.brrenat.seekervault.designsystem

/** Exact-copy fixtures for the six SEE-122 sheet references. */
object SheetCompositionFixtures {
    val WalletHandoff =
        WalletHandoffSheetState(
            walletName = "Seed Vault Wallet",
            walletKind = "Another app",
            headline = "Approve a transaction",
            summary = "Send 5 SOL to FyfWsSPW…YSpEA on Solana devnet",
            explanation =
                "The wallet holds the keys. Seeker Agent Connect only asked; what happens next " +
                    "is decided here.",
            primaryLabel = "Sign and send",
            declineLabel = "Decline",
            leaveLabel = "Leave without answering",
        )

    val Connection =
        ConnectionDetailSheetState(
            title = "studio-mac",
            initials = "SM",
            colour = SourceColour.Tangerine,
            colourOptions =
                listOf(
                    ConnectionColourOption(SourceColour.Tangerine),
                    ConnectionColourOption(SourceColour.Sky, "hermes-box"),
                    ConnectionColourOption(SourceColour.Violet, "runner-node"),
                    ConnectionColourOption(SourceColour.Teal, "CopyTrading demo"),
                    ConnectionColourOption(SourceColour.Rose, "Jupiter Prediction demo"),
                    ConnectionColourOption(SourceColour.Sand),
                ),
            status =
                ConnectionDetailStatus(
                    headline = "Connected · 2 pending",
                    supportingText = "Checked Sep 12, 2026, 9:48 PM",
                    tone = ConnectionDetailStatusTone.Connected,
                ),
            facts =
                listOf(
                    ConnectionDetailFact(
                        "Server",
                        "http://127.0.0.1:8080",
                        FactRowValueStyle.MonoWrap,
                    ),
                    ConnectionDetailFact(
                        "Server ID",
                        "0b83547c-b1c8-4663-b0c6-04e54a9f5f9c",
                        FactRowValueStyle.MonoWrap,
                    ),
                    ConnectionDetailFact(
                        "Paired",
                        "Sep 11, 2026, 11:45 PM",
                        FactRowValueStyle.Plain,
                    ),
                    ConnectionDetailFact(
                        "This phone’s name there",
                        "Seeker",
                        FactRowValueStyle.Plain,
                    ),
                ),
            rules =
                ConnectionDetailRules(
                    title = "Rules",
                    supportingText = "Uses global rules · 2 overrides",
                    caption =
                        "Rules highlight requests that need attention. You still approve every " +
                            "request.",
                ),
            renameLabel = "Rename",
            inboxLabel = "Its inbox",
            disconnectExplanation =
                "The server revokes this phone's credential and cancels its pending requests. To " +
                    "connect again, pair with a new code.",
            disconnectLabel = "Disconnect",
        )

    val ConnectionRules =
        ConnectionRulesSheetState(
            title = "Rules for studio-mac",
            intro =
                "Rules highlight requests that need attention. You still approve every request.",
            introExpanded = false,
            replacementSummary = "2 sections replace the global rules. The rest follow them.",
            replacementCaption = "Global",
            sections = connectionRuleSections(),
            footerCaption = "Swap is out of scope in this build.",
        )

    val GlobalRules =
        GlobalRulesSheetState(
            title = "Global rules",
            intro =
                "Rules highlight requests that need attention. You still approve every request.",
            introExpanded = false,
            defaultsCaption = "These are the defaults every connection starts from.",
            sections = globalRuleSections(),
            clearLabel = "Clear all global rules",
            footerCaption =
                "Daily limits set here count spending across every connection. A connection can " +
                    "add a tighter limit of its own, but cannot raise or remove this one.",
        )

    val AssetEdit =
        AssetEditorSheetState(
            title = "Edit asset",
            saveLabel = "Save",
            scopeLabel = "This connection only",
            kind = AssetEditorKind.NativeSol,
            nativeLabel = "Native SOL",
            tokenLabel = "A token",
            mintLabel = "Token mint address",
            mint = "",
            networkLabel = "Network",
            networks =
                listOf(
                    AssetEditorNetwork("mainnet", "mainnet"),
                    AssetEditorNetwork("devnet", "devnet"),
                    AssetEditorNetwork("testnet", "testnet"),
                ),
            selectedNetwork = 1,
            thresholdsLabel = "Thresholds",
            perRequestLabel = "Most per request",
            perRequest = "6",
            dailyLabel = "Most a day through this connection",
            daily = "8",
            globalDailyTitle = "Global daily limit 10 SOL",
            globalDailySupportingText = "6 SOL used today across all connections · read-only here",
            editGlobalLabel = "Edit",
            footerCaption =
                "A limit here counts only what moves through this connection. The global daily " +
                    "limit still applies on top.",
            canSave = true,
        )

    val AddAddress =
        AddAddressSheetState(
            title = "Add a wallet",
            addLabel = "Add",
            scopeLabel = "This connection only",
            fieldLabel = "Wallet address",
            value = "",
            placeholder = "base58",
            caption =
                "Write the wallet that owns the funds, never a token account. The phone works " +
                    "the owner out of the transaction itself.",
            canAdd = true,
        )

    private fun connectionRuleSections() =
        listOf(
            RulesSheetSection(
                kind = RulesSectionKind.Actions,
                title = "Actions",
                source = ScopeChipSource.Global,
                override = false,
                enabled = true,
                switchLabel = "Only these actions may be asked for",
                statusText = "On in global rules",
                supportingText = "On. Anything not listed is flagged for attention.",
                items = expectedActions(readOnly = true),
            ),
            RulesSheetSection(
                kind = RulesSectionKind.Assets,
                title = "Assets and thresholds",
                source = ScopeChipSource.Connection,
                override = true,
                enabled = true,
                switchLabel = "Only these assets may move",
                statusText = "On in global rules",
                supportingText = "On. Anything not listed is flagged for attention.",
                items =
                    listOf(
                        RulesSheetItem(
                            id = "sol-devnet",
                            model =
                                RuleRowModel(
                                    "Native SOL · devnet",
                                    "6 per request · 8 a day here",
                                    "toll",
                                ),
                            kind = RuleRowKind.Asset,
                        )
                    ),
                addLabel = "Add an asset",
            ),
            RulesSheetSection(
                kind = RulesSectionKind.Recipients,
                title = "Recipients",
                source = ScopeChipSource.Connection,
                override = true,
                enabled = true,
                switchLabel = "Only these wallets may receive funds",
                statusText = "On in global rules",
                supportingText = "On. Anything not listed is flagged for attention.",
                items =
                    listOf(
                        RulesSheetItem(
                            id = "wallet",
                            model =
                                RuleRowModel(
                                    "FyfWsS…YSpEA",
                                    "Wallet that owns the funds",
                                    "account_balance_wallet",
                                ),
                            kind = RuleRowKind.Recipient,
                        )
                    ),
                addLabel = "Add a wallet",
            ),
            RulesSheetSection(
                kind = RulesSectionKind.Programs,
                title = "Programs",
                source = ScopeChipSource.Global,
                override = false,
                enabled = true,
                switchLabel = "Only these programs may be called",
                statusText = "On in global rules",
                supportingText = "On. Anything not listed is flagged for attention.",
                items = programItems(readOnly = true),
            ),
        )

    private fun globalRuleSections() =
        listOf(
            RulesSheetSection(
                kind = RulesSectionKind.Actions,
                title = "Actions",
                source = ScopeChipSource.Global,
                enabled = true,
                switchLabel = "Only these actions may be asked for",
                statusText = "On in global rules",
                supportingText = "On. Anything not listed is flagged for attention.",
                items = expectedActions(readOnly = false),
            ),
            RulesSheetSection(
                kind = RulesSectionKind.Assets,
                title = "Assets and thresholds",
                source = ScopeChipSource.Global,
                enabled = true,
                switchLabel = "Only these assets may move",
                statusText = "On in global rules",
                supportingText = "On. Anything not listed is flagged for attention.",
                items =
                    listOf(
                        RulesSheetItem(
                            id = "sol-devnet",
                            model =
                                RuleRowModel(
                                    "Native SOL · devnet",
                                    "2 per request · 10 a day, all connections",
                                    "toll",
                                ),
                            kind = RuleRowKind.Asset,
                        )
                    ),
                addLabel = "Add an asset",
            ),
            RulesSheetSection(
                kind = RulesSectionKind.Recipients,
                title = "Recipients",
                source = ScopeChipSource.Global,
                enabled = true,
                switchLabel = "Only these wallets may receive funds",
                statusText = "On in global rules",
                supportingText = "On. Anything not listed is flagged for attention.",
                items =
                    listOf(
                        RulesSheetItem(
                            id = "wallet",
                            model =
                                RuleRowModel(
                                    "9xQeWv…sVFin",
                                    "Wallet that owns the funds",
                                    "account_balance_wallet",
                                ),
                            kind = RuleRowKind.Recipient,
                        )
                    ),
                addLabel = "Add a wallet",
            ),
            RulesSheetSection(
                kind = RulesSectionKind.Programs,
                title = "Programs",
                source = ScopeChipSource.Global,
                enabled = true,
                switchLabel = "Only these programs may be called",
                statusText = "On in global rules",
                supportingText = "On. Anything not listed is flagged for attention.",
                items = programItems(readOnly = false),
                addLabel = "Add a program",
            ),
        )

    private fun expectedActions(readOnly: Boolean) =
        listOf("Acknowledge text", "Sign a message", "Transfer funds").mapIndexed { index, text ->
            RulesSheetItem(
                id = index.toString(),
                model = RuleRowModel(text, "Expected", "check_box"),
                kind = RuleRowKind.Action,
                checked = true,
                readOnly = readOnly,
            )
        }

    private fun programItems(readOnly: Boolean) =
        listOf(
            RulesSheetItem(
                id = "system",
                model = RuleRowModel("System Program", "111111…11111", "code"),
                kind = RuleRowKind.Program,
                readOnly = readOnly,
            ),
            RulesSheetItem(
                id = "compute",
                model = RuleRowModel("Compute Budget", "Comput…11111", "code"),
                kind = RuleRowKind.Program,
                readOnly = readOnly,
            ),
        )
}

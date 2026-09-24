package io.github.brrenat.seekervault.operations

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.jupiter.JupiterException
import io.github.brrenat.seekervault.jupiter.JupiterProblem
import io.github.brrenat.seekervault.jupiter.JupiterSwap
import io.github.brrenat.seekervault.jupiter.SOL_MINT
import io.github.brrenat.seekervault.jupiter.SWAP_PREPARATION_LIFETIME
import io.github.brrenat.seekervault.jupiter.SwapFinding
import io.github.brrenat.seekervault.jupiter.USDC_MINT
import io.github.brrenat.seekervault.jupiter.swapTransaction
import io.github.brrenat.seekervault.jupiter.usdcTerms
import io.github.brrenat.seekervault.plugins.JUPITER_PROVIDER
import io.github.brrenat.seekervault.plugins.JUPITER_SWAP
import io.github.brrenat.seekervault.plugins.PROVIDER_CONTRACT
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.SWAP_ACTION
import io.github.brrenat.seekervault.plugins.UnsupportedReason
import io.github.brrenat.seekervault.plugins.actions.SwapParameterNames
import io.github.brrenat.seekervault.plugins.actions.SwapTermNames
import io.github.brrenat.seekervault.proposals.BindingProblem
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.instrumentOf
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.SendResult
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The whole path, from a document a publisher broadcast to a signature (SEE-93).
 *
 * This is where SEE-93's acceptance lives. Two owners act on the same signal with their own
 * amounts; a quote that went stale is prepared again rather than signed; a wallet that declines, or
 * never answers, is recorded honestly; and there is one execution per proposal, ever.
 *
 * Everything is the real code except the provider and the wallet app. The plugin is real, the
 * review is real, the binding and the store are real, and the order things happen in is the app's
 * own.
 */
@RunWith(AndroidJUnit4::class)
class OperationViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    // A view model's own scope is the main one, so the tests drive it: every step here is
    // something the owner did, and each one finishes before the next is asked for.
    private val scheduler = TestCoroutineScheduler()

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @After fun resetMain() = Dispatchers.resetMain()

    private var clock = Instant.parse("2026-09-17T10:00:00Z")

    private val owner = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"

    private fun phone(name: String = "phone") = Phone(File(folder.root, name)) { clock }

    /** Connects a wallet, reads the feed, and opens the one proposal on it. */
    private fun opened(
        phone: Phone,
        address: String = owner,
        network: WalletNetwork = WalletNetwork.Mainnet,
        proposal: io.github.brrenat.seekervault.proposal.v1.Proposal = swapProposal(),
    ): OperationViewModel = runBlocking {
        phone.adapter.answerConnected(address, chains = listOf(network.chain))
        phone.wallet.load()
        phone.wallet.connect(network)
        phone.feed.answers = listOf(proposal)
        val model = phone.viewModel()
        model.refresh(CONNECTION)
        model.open(CONNECTION, proposal.proposalId)
        model
    }

    private fun choose(model: OperationViewModel, amount: ULong, slippage: Int? = null) {
        model.choose(SwapParameterNames.INPUT_AMOUNT, ParameterValue.Amount(amount))
        slippage?.let {
            model.choose(SwapParameterNames.SLIPPAGE_BPS, ParameterValue.Count(it.toUInt()))
        }
    }

    @Test
    fun saysNothingIsKnownUntilTheStoredProposalsHaveBeenRead() {
        // An earlier run of the app: one signal read off the feed and kept on this phone.
        val first = phone()
        runBlocking {
            first.feed.answers = listOf(swapProposal())
            first.proposals.refresh(CONNECTION)
        }
        assertEquals(1, first.proposals.proposals.value.size)

        // The app starts again over the same store. The read waits for the connections, because a
        // proposal is only ever held under the feed it arrived on.
        val restarted = phone()
        restarted.loaded.value = false
        val model = restarted.viewModel()

        // An empty list here is "not read yet", not "this phone holds none". Anything reading an
        // appearing signal as an arrival — the foreground banners (SEE-147) — would otherwise take
        // its baseline here and then announce the whole stored feed as new.
        assertFalse(model.state.value.loaded)
        assertTrue(model.state.value.records.isEmpty())

        restarted.loaded.value = true

        assertTrue(model.state.value.loaded)
        assertEquals(1, model.state.value.records.size)
    }

    @Test
    fun twoOwnersActOnOneSignalWithTheirOwnAmounts() = runBlocking {
        // SEE-93's first acceptance. One document, two phones, two amounts — and neither phone
        // tells the publisher or the gateway anything about its own.
        val published = swapProposal()
        val mine = phone("mine")
        val theirs = phone("theirs")
        val second = "7EqQdEULxWcraVx3mXKFjc84LhCkMGZCkRuDpvcMwJeK"
        val one = opened(mine, proposal = published)
        val other = opened(theirs, address = second, proposal = published)

        choose(one, 2_000_000UL)
        choose(other, 25_000_000UL)
        one.prepare()
        other.prepare()

        val first = checkNotNull(one.review.value)
        val last = checkNotNull(other.review.value)
        assertEquals(Verdict.Verified, first.inspection?.verdict)
        assertEquals(Verdict.Verified, last.inspection?.verdict)
        assertTrue(first.inspection?.approvable == true)
        // Their own amounts, read back out of their own transactions.
        assertEquals(2_000_000UL, first.inspection?.facts?.amount)
        assertEquals(25_000_000UL, last.inspection?.facts?.amount)
        assertEquals(owner, first.inspection?.facts?.wallet)
        assertEquals(second, last.inspection?.facts?.wallet)
        assertFalse(first.prepared?.transaction == last.prepared?.transaction)

        // What each phone asked the gateway: one feed, by its channel, and nothing else. Not the
        // amount, not the wallet, not that a review was opened.
        assertEquals(1, mine.feed.asked.size)
        assertEquals(SOL_MINT, last.record.proposal.value("output_mint"))
        val everythingSaid = mine.feed.asked.joinToString { "${it.first}" }
        assertFalse(everythingSaid.contains(owner))
        assertFalse(everythingSaid.contains("2000000"))
        // And the provider — which does have to be told, because a transaction is built for the
        // account that signs it — was told only this owner's own address and amount.
        assertTrue(mine.provider.asked.any { it.contains(owner) && it.contains("2000000") })
        assertTrue(mine.provider.asked.none { it.contains(second) })
        assertTrue(theirs.provider.asked.none { it.contains(owner) })
    }

    @Test
    fun theAmountIsAskedForAndTheSlippageStartsInsideWhatWasPublished() {
        val model = opened(phone(), proposal = swapProposal(maxSlippageBps = "30"))

        val review = checkNotNull(model.review.value)
        assertTrue(review.served)
        assertNull(review.form.problem)
        assertEquals(
            listOf(SwapParameterNames.INPUT_AMOUNT, SwapParameterNames.SLIPPAGE_BPS),
            review.form.fields.map { it.key },
        )
        // An amount is never suggested — how much of their own money to spend is the one thing
        // nothing here has an opinion about — and the slippage starts at the publisher's ceiling
        // when that is tighter than the app's usual figure.
        assertNull(review.choice[SwapParameterNames.INPUT_AMOUNT])
        assertEquals(
            ParameterValue.Count(30U),
            review.choice[SwapParameterNames.SLIPPAGE_BPS],
        )
        assertEquals(
            USDC_MINT,
            (review.form.fields[0].kind as ParameterKind.Amount).mint,
        )
    }

    @Test
    fun changingTheAmountThrowsAwayWhatWasPreparedForTheOldOne() {
        val model = opened(phone())
        choose(model, 1_000_000UL)
        model.prepare()
        assertNotNull(checkNotNull(model.review.value).prepared)

        choose(model, 3_000_000UL)

        // Bytes for the old number must not stay on screen beside the new one: that is how
        // somebody comes to approve a transaction they are not looking at.
        val review = checkNotNull(model.review.value)
        assertNull(review.prepared)
        assertNull(review.inspection)
        assertFalse(review.acknowledged)
    }

    @Test
    fun aQuoteThatWentStaleIsPreparedAgainRatherThanSigned() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        choose(model, 1_000_000UL)
        model.prepare()
        val prepared = checkNotNull(checkNotNull(model.review.value).prepared)
        assertEquals(
            clock.plus(SWAP_PREPARATION_LIFETIME).epochSecond,
            prepared.expiresAtEpochSeconds,
        )

        // The owner reads, thinks about it, and comes back after the window. The quote is a price
        // from a minute ago and the blockhash is nearly gone, so nothing is signed.
        clock = clock.plus(SWAP_PREPARATION_LIFETIME).plusSeconds(1)
        model.approve(phone.wallet.wallet.value)

        assertEquals(
            OperationProblem.Binding(BindingProblem.PreparationExpired),
            checkNotNull(model.review.value).problem,
        )
        assertEquals(
            emptyList<Triple<ByteString, SelectedWallet, String>>(),
            phone.adapter.sendings,
        )
        assertNull(phone.proposals.proposal(CONNECTION, PROPOSAL)?.execution)

        // Preparing again gets a fresh quote and a fresh window, and then it goes through.
        phone.adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 9 }))
        model.prepare()
        model.approve(phone.wallet.wallet.value)
        assertEquals(1, phone.adapter.sendings.size)
        assertTrue(
            phone.proposals.proposal(CONNECTION, PROPOSAL)?.execution?.outcome
                is ProposalOutcome.Submitted
        )
    }

    @Test
    fun theWalletIsAskedOnceWithExactlyTheBytesThatWereReviewed() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        phone.adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 7 }))
        choose(model, 4_000_000UL)
        model.prepare()
        val reviewed = checkNotNull(checkNotNull(model.review.value).prepared)

        model.approve(phone.wallet.wallet.value)

        assertEquals(1, phone.adapter.sendings.size)
        val (bytes, signed, _) = phone.adapter.sendings.single()
        // The same bytes, byte for byte, and the wallet the screen showed them.
        assertEquals(reviewed.transaction, bytes)
        assertEquals(owner, signed.address)
        val record = checkNotNull(phone.proposals.proposal(CONNECTION, PROPOSAL))
        val execution = checkNotNull(record.execution)
        // And the record says what was bound, written before the wallet opened.
        assertEquals(JUPITER_PROVIDER, execution.binding.provider)
        assertEquals(SWAP_ACTION, execution.binding.action)
        assertEquals(
            4_000_000UL,
            (execution.binding.choice[SwapParameterNames.INPUT_AMOUNT] as ParameterValue.Amount)
                .baseUnits,
        )
        assertEquals(owner, execution.binding.wallet)
        assertEquals(reviewed.version, execution.binding.preparedVersion)
        assertTrue(execution.outcome is ProposalOutcome.Submitted)
        assertTrue(phone.proposals.standing(record) is ProposalStanding.Executed)

        // The owner's own history has it, with what their rules said at the time.
        val history = phone.history.records.value.single()
        assertEquals(ActivityKind.Operation, history.kind)
        assertEquals(ActivityOutcome.Sent, history.outcome)
        assertEquals("swap", history.operation?.operation)
        assertEquals(JUPITER_SWAP.value, history.operation?.plugin)
        assertNotNull(history.policy)
    }

    @Test
    fun aWalletThatDeclinesIsTheOwnersOwnAnswerAndNothingIsRetried() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        phone.adapter.answerSending(SendResult.Declined)
        choose(model, 1_000_000UL)
        model.prepare()

        model.approve(phone.wallet.wallet.value)

        assertEquals(
            ProposalOutcome.Declined,
            phone.proposals.proposal(CONNECTION, PROPOSAL)?.execution?.outcome,
        )
        // One execution per proposal, ever. A second tap finds the first one's record.
        phone.adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 1 }))
        model.prepare()
        model.approve(phone.wallet.wallet.value)
        assertEquals(
            OperationProblem.Binding(BindingProblem.AlreadyExecuted),
            checkNotNull(model.review.value).problem,
        )
        assertEquals(1, phone.adapter.sendings.size)
        assertEquals(
            ProposalOutcome.Declined,
            phone.proposals.proposal(CONNECTION, PROPOSAL)?.execution?.outcome,
        )
    }

    @Test
    fun anAnswerThatNeverArrivedIsNotCalledAFailure() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        // The wallet reported that it signed but could not submit, or the call ended without an
        // answer this phone can read. A signed transaction stays valid until its blockhash
        // expires, so "not submitted here" is not "never sent".
        phone.adapter.answerSending(SendResult.Unknown("the wallet closed"))
        choose(model, 1_000_000UL)
        model.prepare()

        model.approve(phone.wallet.wallet.value)

        val outcome = phone.proposals.proposal(CONNECTION, PROPOSAL)?.execution?.outcome
        assertTrue(outcome is ProposalOutcome.Unresolved)
        assertEquals("the wallet closed", (outcome as ProposalOutcome.Unresolved).detail)
        assertEquals(
            ActivityOutcome.Unknown,
            phone.history.records.value.single().outcome,
        )
        // And nothing asks the wallet again, on its own or on the next look.
        assertEquals(1, phone.adapter.sendings.size)
    }

    @Test
    fun anOperationTheAppClosedOnIsSettledAsUnresolvedRatherThanRetried() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        // The app went away while the wallet held it: the outcome was never recorded.
        phone.adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 4 }))
        choose(model, 1_000_000UL)
        model.prepare()
        val prepared = checkNotNull(checkNotNull(model.review.value).prepared)
        phone.proposals.beginExecution(
            CONNECTION,
            PROPOSAL,
            checkNotNull(model.review.value).let {
                io.github.brrenat.seekervault.proposals.ExecutionBinding(
                    revision = it.record.proposal.revision,
                    environment = it.environment,
                    choice = it.choice,
                    wallet = owner,
                    network = io.github.brrenat.seekervault.request.v1.Network.NETWORK_MAINNET,
                    provider = JUPITER_PROVIDER,
                    action = SWAP_ACTION,
                    schemaVersion = 1,
                    instrument = instrumentOf(it.record.proposal),
                    contract = PROVIDER_CONTRACT,
                    preparedVersion = prepared.version,
                    contentHash =
                        ByteString.copyFrom(
                            java.security.MessageDigest.getInstance("SHA-256")
                                .digest(prepared.transaction.toByteArray())
                        ),
                    expiresAtEpochSeconds = prepared.expiresAtEpochSeconds,
                )
            },
            phone.wallet.wallet.value,
        )
        assertEquals(
            ProposalOutcome.Pending,
            phone.proposals.proposal(CONNECTION, PROPOSAL)?.execution?.outcome,
        )

        phone.proposals.load()

        val outcome = phone.proposals.proposal(CONNECTION, PROPOSAL)?.execution?.outcome
        assertTrue(outcome is ProposalOutcome.Unresolved)
        // The wallet was never asked again for it.
        assertEquals(
            emptyList<Triple<ByteString, SelectedWallet, String>>(),
            phone.adapter.sendings,
        )
    }

    @Test
    fun aProviderThatCannotQuoteIsSaidRatherThanWorkedAround() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        phone.provider.answersQuote = { _, _, _ -> throw JupiterException(JupiterProblem.NoRoute) }
        choose(model, 1_000_000UL)

        model.prepare()

        val review = checkNotNull(model.review.value)
        assertEquals(JupiterProblem.NoRoute.code, review.failure?.code)
        assertNull(review.prepared)
        assertNull(review.inspection)
        assertEquals(
            emptyList<Triple<ByteString, SelectedWallet, String>>(),
            phone.adapter.sendings,
        )
    }

    @Test
    fun bytesThatDoNotDoWhatWasChosenAreNeverPutToTheWallet() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        // A provider that builds a transaction for more than the owner entered. There is no way to
        // ask for one of these, which is why the review has to be the thing that stops it.
        phone.provider.answersBuild = { quote, ownerAddress ->
            JupiterSwap(
                swapTransaction(
                    terms = usdcTerms(quote.inputMint, quote.outputMint),
                    amount = quote.inAmount,
                    quote = quote,
                    owner = ownerAddress,
                    inAmount = quote.inAmount + 1UL,
                )
            )
        }
        choose(model, 1_000_000UL)
        model.prepare()

        val review = checkNotNull(model.review.value)
        assertEquals(Verdict.Invalid, review.inspection?.verdict)
        assertTrue(
            SwapFinding.AmountMismatch.code in review.inspection?.findings.orEmpty().map { it.code }
        )
        assertFalse(review.inspection?.approvable == true)

        // Approving is not offered, and asking anyway does nothing: it is input validation, not a
        // rule anybody could overrule.
        model.approve(phone.wallet.wallet.value)
        assertEquals(
            emptyList<Triple<ByteString, SelectedWallet, String>>(),
            phone.adapter.sendings,
        )
        assertNull(phone.proposals.proposal(CONNECTION, PROPOSAL)?.execution)
    }

    @Test
    fun aWalletOnAnotherNetworkIsRefusedBeforeAnythingIsAsked() = runBlocking {
        val phone = phone()
        val model = opened(phone, network = WalletNetwork.Devnet)
        choose(model, 1_000_000UL)

        model.prepare()

        // Refused by the registry, before a provider was reached at all, and said as itself:
        // "this venue does not serve your cluster" and not "you have no wallet" (SEE-145).
        assertEquals(
            UnsupportedReason.NetworkUnsupported.code,
            checkNotNull(model.review.value).failure?.code,
        )
        // The provider was never asked: a devnet wallet is not a devnet provider, and the app's
        // own devnet transfer tests are about something else entirely.
        assertEquals(emptyList<String>(), phone.provider.asked)
    }

    @Test
    fun aSignalThisPluginCannotReadAsksForNothingAndSaysWhichTermWasWrong() {
        val model =
            opened(phone(), proposal = swapProposal(inputMint = USDC_MINT, maxSlippageBps = "0"))

        val review = checkNotNull(model.review.value)
        assertTrue(review.form.isEmpty)
        val problem = checkNotNull(review.form.problem)
        assertTrue(problem.code.endsWith("max_slippage_bps"))
        assertTrue(problem.invalidates)
    }

    @Test
    fun aProposalTheOwnerHidStaysHiddenAndOffersNothing() = runBlocking {
        val phone = phone()
        val model = opened(phone)

        model.dismiss(CONNECTION, PROPOSAL)

        val record = checkNotNull(phone.proposals.proposal(CONNECTION, PROPOSAL))
        assertTrue(phone.proposals.standing(record) is ProposalStanding.Dismissed)
        // A republication cannot put it back: a dismissal is for the proposal's identity, not for
        // one revision of it (SEE-89).
        phone.feed.answers = listOf(swapProposal(revision = 2))
        model.refresh(CONNECTION)
        assertTrue(
            phone.proposals.standing(checkNotNull(phone.proposals.proposal(CONNECTION, PROPOSAL)))
                is ProposalStanding.Dismissed
        )
    }

    @Test
    fun aSandboxFeedRehearsesEverythingAndOpensNoWallet() = runBlocking {
        // SEE-97's first acceptance, on this side: live data, a real review, a simulated execution
        // and a result nobody could mistake for a purchase.
        val phone = phone()
        phone.keeps(PluginEnvironment.Sandbox)
        val model = opened(phone)
        // The wallet is connected and would answer if it were asked. It is not asked.
        phone.adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 7 }))

        choose(model, 1_000_000UL)
        model.prepare()

        // Everything up to the signature happened, and happened for real: the provider was asked
        // for this owner's own amount, the bytes came back, and this phone read them.
        val review = checkNotNull(model.review.value)
        assertEquals(PluginEnvironment.Sandbox, review.environment)
        assertNotNull(review.prepared)
        assertEquals(Verdict.Verified, review.inspection?.verdict)
        assertTrue(review.inspection?.approvable == true)
        assertTrue(phone.provider.asked.any { it.contains("1000000") })

        model.approve(phone.wallet.wallet.value)

        // And then nothing was signed and nothing was sent. Not a refusal — there is no problem on
        // the screen — but the whole operation, carried out as far as this environment goes.
        assertNull(checkNotNull(model.review.value).problem)
        assertEquals(
            emptyList<Triple<ByteString, SelectedWallet, String>>(),
            phone.adapter.sendings,
        )
        assertEquals(
            emptyList<Triple<ByteString, SelectedWallet, String>>(),
            phone.adapter.signings,
        )

        // The record says what it was: simulated, with no signature to look up, and the binding
        // written all the same — the gate a rehearsal passes is the gate a purchase passes.
        val record = checkNotNull(phone.proposals.proposal(CONNECTION, PROPOSAL))
        val execution = checkNotNull(record.execution)
        assertEquals(ProposalOutcome.Simulated, execution.outcome)
        assertEquals(PluginEnvironment.Sandbox, execution.binding.environment)
        assertEquals(
            1_000_000UL,
            (execution.binding.choice[SwapParameterNames.INPUT_AMOUNT] as ParameterValue.Amount)
                .baseUnits,
        )
        assertTrue(phone.proposals.standing(record) is ProposalStanding.Executed)

        // And so does the owner's own history, which is where they look afterwards.
        val written = phone.history.records.value.single { it.kind == ActivityKind.Operation }
        assertEquals(ActivityOutcome.Simulated, written.outcome)
        assertEquals(PluginEnvironment.Sandbox, written.operation?.environment)
        // No signature, and so no explorer link: a rehearsal has nothing to look up, and this app
        // invents neither.
        assertNull(written.signature)
    }

    @Test
    fun switchingTheEnvironmentUnderAnOpenReviewThrowsAwayWhatWasPrepared() = runBlocking {
        // SEE-97: a preparation, a quote and an approval all belong to the environment they were
        // made in. The owner switched while reading, so they review again.
        val phone = phone()
        phone.keeps(
            PluginEnvironment.Production,
            served = setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
        )
        val model = opened(phone)
        phone.adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 8 }))
        choose(model, 1_000_000UL)
        model.prepare()
        val prepared = checkNotNull(checkNotNull(model.review.value).prepared)

        phone.keeps(
            PluginEnvironment.Sandbox,
            served = setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
        )

        val review = checkNotNull(model.review.value)
        assertEquals(PluginEnvironment.Sandbox, review.environment)
        assertNull(review.prepared)
        assertNull(review.inspection)
        assertFalse(review.acknowledged)
        // Approving now does nothing at all: there is nothing prepared to approve.
        model.approve(phone.wallet.wallet.value)
        assertEquals(
            emptyList<Triple<ByteString, SelectedWallet, String>>(),
            phone.adapter.sendings,
        )
        assertNull(phone.proposals.proposal(CONNECTION, PROPOSAL)?.execution)

        // And the bytes that were prepared under the other promise are refused by the gate itself,
        // which is what stops a switch that lands while an approval is in flight.
        val stale =
            io.github.brrenat.seekervault.proposals.ExecutionBinding(
                revision = 1,
                environment = PluginEnvironment.Production,
                choice = checkNotNull(model.review.value).choice,
                wallet = owner,
                network = io.github.brrenat.seekervault.request.v1.Network.NETWORK_MAINNET,
                provider = JUPITER_PROVIDER,
                action = SWAP_ACTION,
                schemaVersion = 1,
                instrument = instrumentOf(checkNotNull(model.review.value).record.proposal),
                contract = PROVIDER_CONTRACT,
                preparedVersion = prepared.version,
                contentHash =
                    ByteString.copyFrom(
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(prepared.transaction.toByteArray())
                    ),
                expiresAtEpochSeconds = prepared.expiresAtEpochSeconds,
            )
        assertEquals(
            io.github.brrenat.seekervault.connections.ExecutionOutcome.Refused(
                BindingProblem.OtherEnvironment
            ),
            phone.proposals.beginExecution(CONNECTION, PROPOSAL, stale, phone.wallet.wallet.value),
        )
    }

    /**
     * A revision that moved is prepared from its own terms, and never from the ones it replaced.
     *
     * The record, the review that is written down and the binding all follow the latest revision
     * the moment it arrives. If the parsed terms did not follow with them, preparing again would
     * validate and build against the *previous* revision's limits while everything else said the
     * current one — which is a preparation that never has to answer to the terms it was made under.
     */
    @Test
    fun aRevisionThatMovedIsPreparedFromItsOwnTermsRatherThanTheOnesItReplaced() = runBlocking {
        val phone = phone()
        val model =
            opened(
                phone,
                proposal = swapProposal(extra = mapOf(SwapTermNames.MOST_INPUT to "10000000")),
            )
        choose(model, 5_000_000UL, slippage = 50)
        model.prepare()
        assertNotNull(checkNotNull(model.review.value).prepared)

        // The publisher tightened the ceiling on the same pair while the owner was reading.
        phone.feed.answers =
            listOf(swapProposal(revision = 2, extra = mapOf(SwapTermNames.MOST_INPUT to "1000000")))
        model.refresh(CONNECTION)

        val moved = checkNotNull(model.review.value)
        assertEquals(2L, moved.record.proposal.revision)
        assertNull(moved.prepared)
        // The form is the new terms' form, not the old one carried forward.
        assertEquals(
            1_000_000UL,
            (moved.form.fields.single { it.key == SwapParameterNames.INPUT_AMOUNT }.kind
                    as ParameterKind.Amount)
                .most,
        )
        // And the answer they gave to the old terms is not applied to terms they never saw.
        assertNull(moved.choice[SwapParameterNames.INPUT_AMOUNT])

        // The amount the replaced revision allowed is refused against the one on screen, before
        // any provider is asked for bytes.
        choose(model, 5_000_000UL, slippage = 50)
        model.prepare()
        val after = checkNotNull(model.review.value)
        assertNull(after.prepared)
        assertEquals("too_much", after.failure?.code)
        model.approve(phone.wallet.wallet.value)
        assertEquals(
            emptyList<Triple<ByteString, SelectedWallet, String>>(),
            phone.adapter.sendings,
        )
    }

    /**
     * And when the revision repoints the proposal at another pair, the new pair is what is prepared
     * — rather than the old instrument being rebuilt and then refused by the gate until the owner
     * happens to close the review and open it again.
     */
    @Test
    fun aRevisionThatRepointedThePairIsPreparedForThePairItNamesNow() = runBlocking {
        val phone = phone()
        phone.adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 9 }))
        val model = opened(phone)
        choose(model, 1_000_000UL)
        model.prepare()
        assertNotNull(checkNotNull(model.review.value).prepared)

        phone.feed.answers =
            listOf(swapProposal(revision = 2, inputMint = SOL_MINT, outputMint = USDC_MINT))
        model.refresh(CONNECTION)

        val moved = checkNotNull(model.review.value)
        assertEquals(instrumentOf(moved.record.proposal), moved.payload?.instrument)

        choose(model, 1_000_000UL, slippage = 50)
        model.prepare()
        val after = checkNotNull(model.review.value)
        assertNotNull(after.prepared)
        // The provider was asked about the pair the proposal names now.
        assertTrue(phone.provider.asked.last { it.startsWith("quote") }.contains("$SOL_MINT->"))

        // And the binding is for that pair, so the approval gets past the gate rather than being
        // refused as another instrument.
        model.approve(phone.wallet.wallet.value)
        assertEquals(1, phone.adapter.sendings.size)
        assertEquals(
            checkNotNull(after.prepared).transaction,
            phone.adapter.sendings.single().first,
        )
    }

    @Test
    fun termsThatMovedUnderAnOpenReviewThrowAwayWhatWasPreparedForTheOldOnes() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        choose(model, 1_000_000UL)
        model.prepare()
        assertNotNull(checkNotNull(model.review.value).prepared)

        // The publisher raised the revision while the owner was reading.
        phone.feed.answers = listOf(swapProposal(revision = 2, note = "Different terms now."))
        model.refresh(CONNECTION)

        val review = checkNotNull(model.review.value)
        assertEquals(2L, review.record.proposal.revision)
        assertNull(review.prepared)
        assertNull(review.inspection)
        model.approve(phone.wallet.wallet.value)
        assertEquals(
            emptyList<Triple<ByteString, SelectedWallet, String>>(),
            phone.adapter.sendings,
        )
    }
}

package io.github.brrenat.seekervault.operations

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.jupiter.JUPITER_SWAP
import io.github.brrenat.seekervault.jupiter.JupiterException
import io.github.brrenat.seekervault.jupiter.JupiterProblem
import io.github.brrenat.seekervault.jupiter.JupiterSwap
import io.github.brrenat.seekervault.jupiter.SOL_MINT
import io.github.brrenat.seekervault.jupiter.SWAP_PREPARATION_LIFETIME
import io.github.brrenat.seekervault.jupiter.SwapFinding
import io.github.brrenat.seekervault.jupiter.SwapParameterNames
import io.github.brrenat.seekervault.jupiter.USDC_MINT
import io.github.brrenat.seekervault.jupiter.swapTransaction
import io.github.brrenat.seekervault.jupiter.usdcTerms
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.proposals.BindingProblem
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalStanding
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
        assertEquals(JUPITER_SWAP, execution.binding.plugin)
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
                    choice = it.choice,
                    wallet = owner,
                    network = io.github.brrenat.seekervault.request.v1.Network.NETWORK_MAINNET,
                    plugin = JUPITER_SWAP,
                    contract = 1,
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

        assertEquals("other_network", checkNotNull(model.review.value).failure?.code)
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

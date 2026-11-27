package ru.creditcabinet.assistant.domain

/** Whether an answer stays with what the model was given: its figures and the reference's statements. */
object Grounding {
    /** Claims that are false whatever the question: no product of the bank is free, no decision is promised. */
    private val ALWAYS_FALSE = listOf(
        Regex("бесплатн"),
        Regex("не\\s+платн"),
        // "Банк не гарантирует одобрения" is true; only the promise is false.
        Regex("(?<!не\\s)гарантир\\S*\\s+(\\S+\\s+)?одобрен"),
        Regex("одобрени\\S*\\s+гарантир"),
    )

    // Procedures the assistant must not prescribe: a small model was seen to list "steps not to repay".
    private val LEGAL_STEPS = listOf(
        Regex("оформите\\s+отказ"),
        Regex("(?:напишите|подайте)\\s+(?:\\S+\\s+){0,3}(?:отказ|заявлени|иск|жалоб)"),
        Regex("(?:обратитесь|подайте)\\s+(?:\\S+\\s+){0,2}в\\s+суд"),
        Regex("не\\s*возвращ\\S*\\s+(?:\\S+\\s+){0,3}кредит"),
    )

    // Letters of scripts other than Cyrillic and Latin: a small model sometimes slips into Chinese mid-sentence.
    private val FOREIGN_SCRIPT = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\p{IsArabic}]")

    private val INSTRUCTION_ECHO = Regex("""(?m)^\s*(?:вывод|текст)\s+инструкций\s*:""", RegexOption.IGNORE_CASE)

    /** Longer than this the answer is no longer "short"; long answers were where a small model went wrong. */
    const val MAX_ANSWER = 900

    /** Why [answer] cannot be shown; empty when it can. [context] is everything the model was given. */
    fun problems(answer: String, context: String, reference: Article? = null): List<String> = buildList {
        unknownNumbers(answer, context, allowSmall = reference == null).takeIf { it.isNotEmpty() }?.let { add("numbers $it are not in the data") }
        val percentages = PERCENTAGE.findAll(answer).map { normalise(it.groupValues[1]) }.toSet()
        val knownPercentages = PERCENTAGE.findAll(context).map { normalise(it.groupValues[1]) }.toSet()
        if ((percentages - knownPercentages).isNotEmpty()) add("percentage is not in the reference")
        falseClaims(answer).takeIf { it.isNotEmpty() }?.let { add("claims $it contradict the reference") }
        FOREIGN_SCRIPT.find(answer)?.let { add("foreign script '${it.value}'") }
        LEGAL_STEPS.firstNotNullOfOrNull { it.find(answer.lowercase().replace('ё', 'е')) }?.let { add("legal steps '${it.value}'") }
        if (answer.length > MAX_ANSWER) add("too long: ${answer.length} characters")
        if (answer.isBlank()) add("empty")
        if (INSTRUCTION_ECHO.containsMatchIn(answer)) add("instruction echo")
        reference?.let {
            val text = answer.lowercase().replace('ё', 'е')
            val source = it.text.lowercase().replace('ё', 'е')
            if (it.essentialFacts.any { fact -> !fact.containsMatchIn(text) }) add("essential reference facts missing")
            if (it.id == "overdraft") {
                if (hasUnattributedTermLoanCondition(text)) add("term-loan condition attributed to overdraft")
                if ("долгосрочн" in text && "долгосрочн" !in source) add("overdraft duration restriction absent from the reference")
                if (OVERDRAFT_SIZE_OR_TERM.containsMatchIn(text)) add("overdraft amount or term restriction absent from the reference")
            }
            if (it.id == "load" && LOAD_THRESHOLD_BY_PRODUCT.containsMatchIn(text)) add("product-specific load threshold absent from the reference")
            val hasDocuments = it.id == "investment" || DOCUMENT_WORDS.containsMatchIn(source)
            if ((!hasDocuments && DOCUMENT_WORDS.containsMatchIn(text)) ||
                SPECIFIC_DOCUMENTS.any { word -> word in text && word !in source } ||
                ("согласи" in text && "соглас" !in source)
            ) {
                add("requirements absent from the reference")
            }
        }
    }

    /** The statements of [answer] that contradict the reference. */
    fun falseClaims(answer: String): List<String> {
        val text = answer.lowercase().replace('ё', 'е')
        return (Knowledge.articles.flatMap { it.falseClaims } + ALWAYS_FALSE).mapNotNull { claim -> claim.find(text)?.value }
    }

    // Digits with thousand separators (spaces of any kind) and a decimal part: «1 500 000», «64», «21,5».
    private val NUMBER = Regex("\\d[\\d\\u00A0\\u202F ]*(?:[.,]\\d+)?")
    private val PERCENTAGE = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:%|процент[а-я]*)", RegexOption.IGNORE_CASE)
    private val DOCUMENT_WORDS = Regex("документ|справк|спецификац|декларац|выписк|отчетност")
    private val SPECIFIC_DOCUMENTS = listOf("паспорт", "трудов", "декларац", "выписк", "отчетност")
    private val LOAD_THRESHOLD_BY_PRODUCT = Regex("""порог[^.!?\n]{0,35}завис\p{L}*\s+от[^.!?\n]{0,25}продукт""")
    private val OVERDRAFT_SIZE_OR_TERM = Regex("""небольш\p{L}*\s+сумм|коротк\p{L}*\s+(?:период|срок)""")
    private val TERM_LOAN_CONDITION = Regex(
        "(?:одн|един)\\p{L}*\\s+сумм\\p{L}*|единовремен\\p{L}*|равн\\p{L}*\\s+платеж\\p{L}*|аннуитет\\p{L}*",
    )
    private val WORKING_CAPITAL_SUBJECT = Regex(
        "(?<!\\p{L})кредит\\s+на\\s+пополнение\\s+оборот(?:а|ных\\s+средств)\\s+" +
            "(?:выдается|выдают|гасится|погашается)(?!\\p{L})",
    )

    private fun hasUnattributedTermLoanCondition(text: String): Boolean = text.splitToSequence('.', '!', '?', '\n').any { sentence ->
        val condition = TERM_LOAN_CONDITION.find(sentence) ?: return@any false
        !WORKING_CAPITAL_SUBJECT.containsMatchIn(sentence.substring(0, condition.range.first))
    }

    private fun normalise(number: String): String {
        val compact = number.replace(Regex("[\\s\\u00A0\\u202F]"), "").replace(',', '.')
        return compact.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: compact
    }

    fun numbers(text: String): Set<String> = NUMBER.findAll(text).map { normalise(it.value.trim()) }.toSet()

    /** Numbers of [answer] that appear nowhere in [context]. Staff summaries can number their points. */
    fun unknownNumbers(answer: String, context: String, allowSmall: Boolean = true): Set<String> {
        val known = numbers(context)
        return numbers(answer)
            .filter {
                it !in known &&
                    (
                        !allowSmall ||
                            (
                                it.toBigDecimalOrNull()?.let { value ->
                                    value > java.math.BigDecimal(SMALL)
                                } ?: true
                                )
                        )
            }.toSet()
    }

    private const val SMALL = 10
}

/** Answers without a model: the matching article and the facts of the application, put together by rules. */
object RuleAnswers {
    /** The answer from the application's facts, when the question is about them; null otherwise. */
    fun aboutApplication(question: String, facts: ApplicationFacts?): String? {
        facts ?: return null
        val text = question.lowercase()
        return when {
            ("отказ" in text || "почему" in text || "отклон" in text) && facts.refusal.isNotEmpty() ->
                "По заявке № ${facts.number} банк отказал.\n" + facts.refusal.joinToString("\n") { "— $it" }
            OFFER_WORDS.any { it in text } && facts.offer != null -> "${facts.offer}."
            // Asked for "approved terms" before the bank has made an offer, a model was seen to invent one.
            facts.offer == null && facts.refusal.isEmpty() && asksForBankTerms(text) -> noOfferYet(facts)
            ("документ" in text || "загруз" in text) && facts.missingDocuments.isNotEmpty() ->
                "Для заявки № ${facts.number} нужны: ${facts.missingDocuments.joinToString("; ")}" + (facts.deadline?.let { ". Срок — до $it." } ?: ".")
            isAboutStatus(text) -> "Заявка № ${facts.number}: ${facts.status}."
            else -> null
        }
    }

    /** "What did the bank approve", "my terms": the bank's terms for this application, not a product's terms in general. */
    private fun asksForBankTerms(text: String): Boolean {
        val words = text.split(Regex("[^а-яё]+"))
        val aboutApplication = words.any { it in PERSONAL } || "заявк" in text || "черновик" in text
        return "одобр" in text || (NO_OFFER_WORDS.any { it in text } && aboutApplication)
    }

    private fun noOfferYet(facts: ApplicationFacts): String =
        listOfNotNull(
            "По заявке № ${facts.number} банк ещё не одобрил условия и предложения банка нет. Сейчас: ${facts.status}.",
            facts.estimate?.let { "$it — это расчёт кабинета, а не условия банка." },
            "Одобренные сумма, срок, ставка и платёж появятся в заявке, когда банк сделает предложение.",
        ).joinToString("\n")

    /** "Give legal advice", "how not to repay": outside what the assistant may answer. */
    fun asksForLegalAdvice(question: String): Boolean {
        val text = question.lowercase().replace('ё', 'е')
        return (LEGAL_WORDS + EVASION).any { it.containsMatchIn(text) }
    }

    const val NO_LEGAL_ADVICE =
        "Юридических советов я не даю и не подскажу, как не возвращать кредит. Если платить становится трудно, " +
            "напишите банку заранее в чате заявки — специалист расскажет, какие есть варианты. " +
            "Я подскажу, как устроены продукты и рассмотрение заявки и что означает решение банка."

    /** A question about "my" refusal, offer or status — it needs an application to answer. */
    fun isAboutOwnApplication(question: String): Boolean {
        val words = question.lowercase().split(Regex("[^а-яё]+"))
        val personal = words.any { it in PERSONAL }
        val text = question.lowercase()
        return personal && (OWN_TOPICS.any { it in text } || isAboutStatus(text))
    }

    private fun isAboutStatus(text: String): Boolean = STATUS_WORDS.any { it in text } || ("заявк" in text && STATUS_HINTS.any { it in text })

    fun openTheApplication(articles: List<Article>): String =
        listOf(
            "Про вашу заявку я отвечаю по её данным, а сейчас заявка не открыта. Откройте её в списке заявок и задайте вопрос там: " +
                "причины решения, условия предложения и сроки возьму из неё.",
        ).plus(articles.take(1).map { it.text }).joinToString("\n\n")

    fun answer(question: String, facts: ApplicationFacts?, articles: List<Article>): String {
        val parts = listOfNotNull(aboutApplication(question, facts)) + articles.take(2).map { it.text }
        if (parts.isNotEmpty()) return parts.joinToString("\n\n")
        return "Я подскажу, как устроены продукты и рассмотрение заявки, что означает статус, предложение или отказ банка и какие нужны документы. " +
            "Например: «Чем овердрафт отличается от кредита?», «Почему мне предложили меньшую сумму?», «Какие документы загрузить?»."
    }

    // "Юридическое лицо" is a client, not a request for advice: only advice, help or a lawyer count.
    private val LEGAL_WORDS = listOf(
        Regex("юридическ\\S*\\s+(?:совет|помощ|консультац)"),
        Regex("(?<![а-я])(?:юрист|адвокат)"),
        Regex("(?<![а-я])в\\s+суд(?![а-я])|судебн"),
    )
    private val EVASION = listOf(
        Regex("не\\s*(?:возвращ|отда|плат|гас)\\S*\\s+(?:\\S+\\s+){0,2}(?:кредит|долг|деньг)"),
        Regex("(?:кредит|долг)\\S*\\s+(?:\\S+\\s+){0,2}не\\s*(?:возвращ|отда|плат|гас)"),
        Regex("уклон\\S*\\s+от\\s+(?:уплат|платеж|погашен)"),
        Regex("списа\\S*\\s+(?:\\S+\\s+){0,2}(?:кредит|долг)"),
    )
    private val NO_OFFER_WORDS = listOf("предлож", "услови", "одобр", "ставк")
    private val OFFER_WORDS = listOf("предлож", "услови", "ставк", "платеж", "платёж", "меньш", "друг")
    private val PERSONAL = setOf("мне", "меня", "мой", "моя", "мою", "моей", "моего", "нам", "нас", "наш", "наша", "нашу", "нашей")
    private val OWN_TOPICS = listOf("отказ", "отклон", "одобр", "предлож", "услови", "документ")
    private val STATUS_HINTS = listOf("сейчас", "что с ", "что там", "на каком этапе", "где ")
    private val STATUS_WORDS = listOf("статус", "что с заявк", "что дальше", "сколько ждать", "когда")
}

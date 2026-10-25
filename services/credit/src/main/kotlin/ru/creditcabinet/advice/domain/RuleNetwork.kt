package ru.creditcabinet.advice.domain

/**
 * A rule of a parameter–rule network: it reads its input parameters and, when they are all known, gives
 * its outputs — or nothing, when its condition does not hold. Parameters and rules form a
 * bipartite graph; the engine finds the order of rules from what is known to what is asked.
 */
class NetworkRule(
    val id: String,
    /** What the rule establishes, in the underwriter's words; shown in the chain «почему». */
    val title: String,
    val inputs: List<String>,
    val outputs: List<String>,
    /** The statement with its values, from the inputs and outputs together: «Нагрузка — 47% выручки». */
    val explain: (Map<String, Any>) -> String = { title },
    private val apply: (Map<String, Any>) -> Map<String, Any>?,
) {
    fun fire(known: Map<String, Any>): Map<String, Any>? = apply(inputs.associateWith { known.getValue(it) })
}

/** One step of the inference: which rule fired, on which values, and what it established. */
data class Firing(val ruleId: String, val title: String, val statement: String, val inputs: Map<String, Any>, val outputs: Map<String, Any>)

class Inference(val known: Map<String, Any>, val firings: List<Firing>) {
    private val producer: Map<String, Firing> = firings.flatMap { firing -> firing.outputs.keys.map { it to firing } }.toMap()

    /** The rules that led to [parameter], earliest first: the explanation of a conclusion. */
    fun chain(parameter: String): List<Firing> {
        val seen = LinkedHashSet<Firing>()

        fun visit(name: String) {
            val firing = producer[name] ?: return
            if (firing in seen) return
            firing.inputs.keys.forEach(::visit)
            seen += firing
        }
        visit(parameter)
        return seen.toList()
    }
}

/**
 * A model of parameters and rules with forward inference. Each rule fires at most once, as soon
 * as all its inputs are known; a value once known is never overwritten, so the result does not
 * depend on the order the rules are written in.
 */
class RuleNetwork(val version: Int, val rules: List<NetworkRule>) {
    init {
        val ids = rules.map { it.id }
        require(ids.size == ids.toSet().size) { "Rule ids repeat: $ids" }
        val producers = rules.flatMap { rule -> rule.outputs.map { it to rule.id } }.groupBy({ it.first }, { it.second })
        require(producers.values.all { it.size == 1 }) { "A parameter has more than one rule giving it: ${producers.filterValues { it.size > 1 }}" }
    }

    fun infer(facts: Map<String, Any?>): Inference {
        val known = facts.filterValues { it != null }.mapValues { it.value!! }.toMutableMap()
        val pending = rules.toMutableList()
        val firings = mutableListOf<Firing>()
        var progressed = true
        while (progressed) {
            progressed = false
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val rule = iterator.next()
                if (!rule.inputs.all { it in known }) continue
                iterator.remove()
                progressed = true
                val outputs = rule.fire(known)?.filterKeys { it in rule.outputs && it !in known } ?: continue
                if (outputs.isEmpty()) continue
                val inputs = rule.inputs.associateWith { known.getValue(it) }
                known += outputs
                firings += Firing(rule.id, rule.title, rule.explain(inputs + outputs), inputs, outputs)
            }
        }
        return Inference(known, firings)
    }
}

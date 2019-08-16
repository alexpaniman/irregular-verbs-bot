import java.io.File

val words = File("src/main/resources/irregular-verbs.txt")
    .readLines()
    .map { it.split("#") }
    .toTypedArray()

data class Answer(val translate: Boolean, val verb: List<String>, var answer: String? = null) {
    val representation: String
        get() = if (translate) verb[3]
                else verb[0]

    val question: String
        get() = if (translate) "`Enter: [1st], [2nd], [3rd]`"
                else "`Enter: [russian], [2nd], [3rd]`"

    val expected: String
        get() = if (translate) verb[0] + ", " + verb[1] + ", " + verb[2]
                else verb[3] + ", " + verb[0] + ", " + verb[1]

    private var test: Boolean? = null

    fun test(): Boolean {
        if (test != null)
            return test!!
        val list = "[A-Za-z/]+".toRegex()
            .findAll(answer!!)
            .toList()
            .map { it.value.toLowerCase() }
        if (list.size != 3) {
            test = false
            return false
        }
        test = (if (translate) list[0] == verb[0]
                      else     list[0] in verb[3].split(", "))
                &&             list[1] == verb[1]
                &&             list[2] == verb[2]
        return test!!
    }
}

val statistics = HashMap<Long, ArrayList<Answer>>()

val bot = botconfig("<bot-username>", "<bot-token>") {
    handler("/start") {
        while(true) {
            val stats = statistics.computeIfAbsent(chat) { ArrayList() }
            val answer = Answer(
                true,
                words.map { verb ->
                        stats
                            .filter { it.verb == verb }
                            .count()
                    }.min().let { min ->
                        words.filter { verb ->
                            stats
                                .filter { it.verb == verb }
                                .count() == min
                        }.random()
                    }
            )
            send("""
                | `Correct answers:` `${
                    stats
                        .filter { it.test() }
                        .count()
                }` `~=` `${
                    if (stats.count() == 0)
                        "`?`"
                    else stats
                        .filter { it.test() }
                        .count() * 100 / stats.count()
                } %`
                | `Wrong answers  :` `${
                    stats
                        .filter { !it.test() }
                        .count()
                }` `~=` `${
                    if (stats.count() == 0)
                        "`?`"
                    else stats
                        .filter { !it.test() }
                        .count() * 100 / stats.count()
                } %`
                | `Total          :` `${ stats.count() }`
                | 
                | `Irregular verb :` *** ${ answer.representation.toLowerCase() } ***
                | ${ answer.question }
            """.trimMargin("| ")) { enableMarkdown(true) }
            answer.answer = ask()
            if (answer.test())
                send("`Correct!`") {
                    enableMarkdown(true)
                }
            else
                send("`Wrong! Expected: ${ answer.expected.toLowerCase() }`") {
                    enableMarkdown(true)
                }
            stats += answer
        }
    }
}

fun main() = bot.pooling()

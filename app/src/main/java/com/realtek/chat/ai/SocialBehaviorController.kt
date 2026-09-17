package com.realtek.chat.ai

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.realtek.chat.settings.AppSettings
import com.realtek.chat.storage.AppDb
import com.realtek.chat.storage.Contact
import kotlin.math.max
import kotlin.math.min

data class SocialActionDecision(
    val continueSpeaking: Boolean,
    val mode: String,
    val direction: String,
    val question: Boolean,
    val delayClass: String
)

data class ConversationPressure(
    val score: Double,
    val summary: String
)

data class GroupSystemDecision(
    val speak: Boolean,
    val speakerContactId: Int?,
    val mode: String,
    val direction: String,
    val targetLabel: String,
    val delayClass: String
)

class SocialBehaviorController(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDb.get(appContext)
    private val settings = AppSettings(appContext)
    private val gateway = RunApiGateway(appContext)
    private val socialEngine = SocialEngine(appContext)
    private val personaEvolution =
        PersonaEvolutionEngine(
            appContext
        )

    fun shouldConsiderContinuation(
        userText: String
    ): Boolean {
        val clean = userText.trim()

        if (isExplicitContinueRequest(clean)) {
            return true
        }

        if (isClosingSignal(clean)) {
            return false
        }

        // 文字聊天允许消息交叉。
        // 用户可能继续发送，不构成自动停止理由。
        return true
    }

    suspend fun decideNextExtraMessage(
        contact: Contact,
        originalUserText: String,
        explicitContinue: Boolean
    ): SocialActionDecision {
        val empty = SocialActionDecision(
            continueSpeaking = false,
            mode = "stop",
            direction = "",
            question = false,
            delayClass = "normal"
        )

        if (
            !settings.decisionConfigured()
        ) {
            return empty
        }

        val pressure = conversationPressure(
            contact.id
        )

        val recent = db.getRecentMessages(
            contact.id,
            12
        )

        val transcript = recent.joinToString("\n") {
            "${if (it.role == "user") "用户" else contact.name}：" +
                if (it.type == "image") "[图片]" else it.text
        }

        val state = socialEngine.snapshot(
            contact.id
        )

        val adaptivePersona =
            personaEvolution
                .effectiveRules(
                    contact.id
                )

        val raw = runCatching {
            gateway.chat(
                provider =
                    settings.decisionProvider,
                model =
                    settings.decisionModel,
                systemPrompt = """
                    你是 Realtek 的统一社交行为判断模型。
                    你不负责写“${contact.name}”最终要发送的聊天内容，
                    只负责判断这个联系人现在是否还应该继续说，以及下一条属于什么行为。

                    你现在只判断：联系人刚发完一条以后，是否值得“再单独发一条”。

                    联系人初始核心性格：
                    ${contact.corePersona}

                    联系人初始说话方式：
                    ${contact.styleRules}

                    与当前用户相处后形成的动态规则：
                    $adaptivePersona

                    动态规则优先于初始表达习惯。
                    初始人物卡中的“固定几条、每次必须、固定加表情、总要追问”
                    不能作为机械行为规则。

                    最重要的规则：
                    1. 核心判断是“当前聊天动量还在不在”，不是审查“额外消息够不够珍贵”。
                    2. 只要思路仍在自然延伸，或出现第二反应、补充、联想、调侃、观点推进、自然追问，continue=true。
                    3. 不要因为已经发过一条、两条、三条就提高门槛；消息条数本身不是停止理由。
                    4. 不要默认“一次完整回答以后就该停”。文字聊天里一个人完全可能连续发多条。
                    5. 只有明确收尾、话题自然落地、继续只会复述/凑话、或明显造成压迫感时才 continue=false。
                    6. 用户后来继续发消息不要求你自动停；最近聊天才是当前最权威上下文。
                    7. social_drive、人物性格和关系只影响倾向，不形成固定“话多/话少”模板。
                    8. conversation pressure 是参考信号，不是硬阈值；几条短而有内容的连续消息完全可以自然。
                    9. 不要为了保持聊天而硬问问题，也不要为了显得克制而过早结束。
                    10. 行为由内容和聊天动量产生，不靠固定条数、轮次或周期。
                """.trimIndent(),
                userPrompt = """
                    联系人：${contact.name}

                    最初触发当前表达的用户消息：
                    $originalUserText

                    注意：之后用户可能又发了新消息。
                    “最近聊天”才是当前最权威上下文；不要因为用户继续发消息就自动停止。

                    当前联系人已经连续表达过若干消息。
                    不要按“这是第几条”决定停不停，只判断此刻聊天动量。

                    用户是否明确要求继续说：
                    $explicitContinue

                    当前连续聊天状态：
                    mood=${state.mood}
                    topic=${state.currentTopic}
                    social_drive=${"%.2f".format(state.socialDrive)}

                    双方关系直接根据人物设定、动态人格和最近聊天理解，
                    不根据创建时间或消息数量自动改变。

                    对话压力：
                    score=${"%.2f".format(pressure.score)}
                    ${pressure.summary}

                    最近聊天：
                    $transcript

                    最近联系人自我话题：
                    ${socialEngine.recentTopicSummary(contact.id)}

                    只输出 JSON：
                    {
                      "continue": true,
                      "mode": "afterthought",
                      "direction": "顺着当前话题再自然补一个想法",
                      "question": false,
                      "delay_class": "normal"
                    }

                    mode 只能是：
                    reaction
                    afterthought
                    clarification
                    self_share
                    followup_question
                    topic_extension

                    delay_class 只能是：
                    quick
                    normal
                    thoughtful

                    不需要给“新颖度/独立价值”打分。
                    你只需要判断：此刻聊天动量还在不在；如果还在，就继续。
                """.trimIndent(),
                maxTokens = 170
            )
        }.getOrNull() ?: return empty

        val obj = parseJson(raw) ?: return empty

        val decision = SocialActionDecision(
            continueSpeaking =
                obj["continue"]
                    ?.takeIf {
                        it.isJsonPrimitive
                    }
                    ?.asBoolean
                    ?: false,
            mode =
                obj["mode"]
                    ?.takeIf {
                        it.isJsonPrimitive
                    }
                    ?.asString
                    ?.trim()
                    ?.takeIf {
                        it in setOf(
                            "reaction",
                            "afterthought",
                            "clarification",
                            "self_share",
                            "followup_question",
                            "topic_extension"
                        )
                    }
                    ?: "afterthought",
            direction =
                obj["direction"]
                    ?.takeIf {
                        it.isJsonPrimitive
                    }
                    ?.asString
                    ?.trim()
                    .orEmpty()
                    .take(220),
            question =
                obj["question"]
                    ?.takeIf {
                        it.isJsonPrimitive
                    }
                    ?.asBoolean
                    ?: false,
            delayClass =
                obj["delay_class"]
                    ?.takeIf {
                        it.isJsonPrimitive
                    }
                    ?.asString
                    ?.trim()
                    ?.takeIf {
                        it in setOf(
                            "quick",
                            "normal",
                            "thoughtful"
                        )
                    }
                    ?: "normal"
        )

        // 系统行为模型已经看到最新上下文、persona、social_drive 和 conversation pressure。
        // 这里不再叠加“第2条/第3条越来越难”的本地硬门槛，避免双重保守。
        return if (
            decision.continueSpeaking
        ) {
            decision
        } else {
            empty
        }
    }

suspend fun decideGroupNextSpeaker(
    groupId: Int,
    members: List<Contact>,
    aiTurnsSinceUser: Int
): GroupSystemDecision {
    val stop =
        GroupSystemDecision(
            speak = false,
            speakerContactId = null,
            mode = "wait",
            direction = "",
            targetLabel = "",
            delayClass = "normal"
        )

    if (
        members.size < 2 ||
        !settings.decisionConfigured()
    ) {
        return stop
    }

    val recent =
        db.getRecentGroupMessages(
            groupId,
            20
        )

    if (
        recent.isEmpty()
    ) {
        return stop
    }

    val roster =
        members.joinToString(
            "\n\n"
        ) {
            contact ->
            val adaptive =
                personaEvolution
                    .effectiveRules(
                        contact.id
                    )
                    .take(
                        700
                    )

            val state =
                socialEngine.snapshot(
                    contact.id
                )

            """
            [CONTACT:${contact.id}|${contact.name}]
            初始人格：${contact.corePersona.take(600)}
            初始表达：${contact.styleRules.take(450)}
            动态相处规则：$adaptive
            social_drive=${"%.2f".format(state.socialDrive)}
            """.trimIndent()
        }

    val transcript =
        recent.joinToString(
            "\n"
        ) {
            message ->
            val speaker =
                if (
                    message.senderKind ==
                        "user"
                ) {
                    "[USER:user|${message.senderName}]"
                } else {
                    val stableId =
                        message.senderKey
                            .removePrefix(
                                "contact:"
                            )

                    "[CONTACT:$stableId|${message.senderName}]"
                }

            "$speaker：${message.text}"
        }

    val raw =
        runCatching {
            gateway.chat(
                provider =
                    settings.decisionProvider,
                model =
                    settings.decisionModel,
                systemPrompt = """
                    你是 Realtek 群聊唯一的社交行为判断模型。
                    每一步只由你判断：
                    1. 群聊现在应该停止，还是应该有人继续说；
                    2. 如果继续，只选择一个最自然的联系人；
                    3. 指定他的行为类型、回应方向、目标对象和等待类型。

                    你不负责写最终聊天台词。
                    被选中的联系人之后会使用他自己的聊天模型生成真正内容。

                    【身份规则】
                    USER:user 是用户本人。
                    每个 CONTACT:id 都是独立成员。
                    A说的话不能算成B说的，也不能算成用户说的。
                    不要把不同联系人合并成一个人。

                    【群成员】
                    $roster

                    判断原则：
                    - 先判断“当前群聊动量是否仍然存在”，再决定 stop 或选下一位。
                    - 不要求所有联系人都回应用户，也绝对不要为了公平而轮流安排每个人说一句。
                    - 同一个联系人可以连续说两条、三条甚至更多；A和B也可以连续来回很多轮，C可以一直不说话。
                    - 联系人可以回应用户，也可以回应另一个联系人。
                    - 消息数量、已经连续了多少轮，本身都不是停止理由。
                    - 只要最新内容产生了自然反应、反驳、补充、追问、玩笑、观点推进或新的关联，优先保持聊天动量。
                    - 只有话题真的自然落地、无人有新内容、继续只会复述/凑话时才 stop。
                    - 不因为“用户可能还在输入”而自动 stop；文字聊天允许消息交叉。
                    - 不要为了热闹强行制造内容，但也不要为了克制而过早停掉正在进行的对话。
                    - 人物动态规则优先于初始表达习惯。
                """.trimIndent(),
                userPrompt = """
                    最近群聊：
                    $transcript

                    自从用户最后一条消息后，
                    已连续出现 AI 消息：
                    $aiTurnsSinceUser 条

                    这个数字只用于理解当前节奏，不能作为“聊多了就该停”的规则。
                    只根据最新内容和群聊动量决定下一步。

                    只输出 JSON。

                    如果继续：
                    {
                      "action": "speak",
                      "speaker_id": 3,
                      "mode": "reply",
                      "direction": "自然回应另一个联系人的观点",
                      "target": "[CONTACT:7|B]",
                      "delay_class": "normal"
                    }

                    如果该停：
                    {
                      "action": "stop",
                      "speaker_id": null,
                      "mode": "wait",
                      "direction": "",
                      "target": "",
                      "delay_class": "normal"
                    }

                    speaker_id 必须是群成员 CONTACT id。
                    mode 可以是：
                    reaction
                    reply
                    clarify
                    self_share
                    topic_extend
                    question
                    wait

                    delay_class 只能是：
                    quick
                    normal
                    thoughtful
                """.trimIndent(),
                maxTokens = 180
            )
        }.getOrNull()
            ?: return stop

    val obj =
        parseJson(
            raw
        ) ?: return stop

    val action =
        obj["action"]
            ?.takeIf {
                it.isJsonPrimitive
            }
            ?.asString
            ?.trim()
            .orEmpty()

    if (
        action != "speak"
    ) {
        return stop
    }

    val speakerId =
        obj["speaker_id"]
            ?.takeIf {
                it.isJsonPrimitive
            }
            ?.asInt

    if (
        speakerId == null ||
        members.none {
            it.id == speakerId
        }
    ) {
        return stop
    }

    return GroupSystemDecision(
        speak = true,
        speakerContactId =
            speakerId,
        mode =
            obj["mode"]
                ?.takeIf {
                    it.isJsonPrimitive
                }
                ?.asString
                ?.trim()
                .orEmpty()
                .ifBlank {
                    "reply"
                },
        direction =
            obj["direction"]
                ?.takeIf {
                    it.isJsonPrimitive
                }
                ?.asString
                ?.trim()
                .orEmpty()
                .take(
                    220
                ),
        targetLabel =
            obj["target"]
                ?.takeIf {
                    it.isJsonPrimitive
                }
                ?.asString
                ?.trim()
                .orEmpty()
                .take(
                    120
                ),
        delayClass =
            obj["delay_class"]
                ?.takeIf {
                    it.isJsonPrimitive
                }
                ?.asString
                ?.trim()
                ?.takeIf {
                    it in setOf(
                        "quick",
                        "normal",
                        "thoughtful"
                    )
                }
                ?: "normal"
    )
}

    fun delayFor(
        contact: Contact,
        decision: SocialActionDecision,
        previousText: String
    ): Long {
        var base =
            when (
                decision.delayClass
            ) {
                "quick" -> 650L
                "thoughtful" -> 2_350L
                else ->
                    when (
                        decision.mode
                    ) {
                        "reaction" -> 800L
                        "afterthought" -> 1_350L
                        "clarification" -> 1_650L
                        "followup_question" -> 1_750L
                        "topic_extension" -> 1_950L
                        "self_share" -> 2_150L
                        else -> 1_350L
                    }
            }

        base +=
            when {
                previousText.length > 180 -> 700L
                previousText.length > 80 -> 380L
                previousText.length > 30 -> 160L
                else -> 0L
            }

        val style =
            (
                contact.corePersona +
                    " " +
                    contact.styleRules
                ).lowercase()

        if (
            listOf(
                "反应快",
                "活泼",
                "健谈"
            ).any {
                style.contains(it)
            }
        ) {
            base -= 160L
        }

        if (
            listOf(
                "慢热",
                "克制",
                "沉稳"
            ).any {
                style.contains(it)
            }
        ) {
            base += 220L
        }

        return base.coerceIn(
            450L,
            4_800L
        )
    }

    fun conversationPressure(
        contactId: Int
    ): ConversationPressure {
        val recent = db.getRecentMessages(
            contactId,
            14
        )

        if (recent.isEmpty()) {
            return ConversationPressure(
                0.0,
                "暂无足够历史。"
            )
        }

        val user = recent.filter {
            it.role == "user"
        }

        val assistant = recent.filter {
            it.role == "assistant"
        }

        val userChars = user.sumOf {
            it.text.length
        }

        val assistantChars = assistant.sumOf {
            it.text.length
        }

        val totalChars = max(
            1,
            userChars + assistantChars
        )

        val assistantCharShare =
            assistantChars.toDouble() /
                totalChars.toDouble()

        val consecutiveAssistant =
            recent.asReversed()
                .takeWhile {
                    it.role == "assistant"
                }
                .size

        val assistantQuestions =
            assistant.count {
                it.text.contains("?") ||
                    it.text.contains("？")
            }

        val lastAssistantLength =
            recent.asReversed()
                .firstOrNull {
                    it.role == "assistant"
                }
                ?.text
                ?.length
                ?: 0

        val latestUser =
            recent.asReversed()
                .firstOrNull {
                    it.role == "user"
                }

        val latestUserIndex =
            latestUser?.let {
                recent.indexOf(it)
            } ?: -1

        val assistantBeforeLatestUser =
            if (latestUserIndex > 0) {
                recent.subList(
                    0,
                    latestUserIndex
                )
                    .asReversed()
                    .firstOrNull {
                        it.role == "assistant"
                    }
            } else {
                null
            }

        val terseAfterHeavy =
            latestUser != null &&
                latestUser.text.length <= 5 &&
                (
                    assistantBeforeLatestUser
                        ?.text
                        ?.length
                        ?: 0
                    ) >= 90

        var score =
            assistantCharShare * 0.38 +
                min(
                    1.0,
                    consecutiveAssistant / 3.0
                ) * 0.25 +
                min(
                    1.0,
                    assistantQuestions / 4.0
                ) * 0.14 +
                min(
                    1.0,
                    lastAssistantLength / 350.0
                ) * 0.13

        if (terseAfterHeavy) {
            score += 0.10
        }

        score = score.coerceIn(
            0.0,
            1.0
        )

        val summary =
            "最近${recent.size}条：" +
                "用户${user.size}条/${userChars}字；" +
                "联系人${assistant.size}条/${assistantChars}字；" +
                "联系人连续发言${consecutiveAssistant}条；" +
                "问句${assistantQuestions}条；" +
                "最近联系人消息${lastAssistantLength}字；" +
                "用户是否在长回复后变短=${terseAfterHeavy}。"

        return ConversationPressure(
            score,
            summary
        )
    }

    private fun isClosingSignal(
        text: String
    ): Boolean {
        val signals = listOf(
            "晚安",
            "拜拜",
            "先这样",
            "不聊了",
            "回头说",
            "等会聊",
            "一会再说",
            "我先忙了",
            "先忙了",
            "我开车去了",
            "开车去了",
            "我上课去了",
            "上课去了",
            "我要睡了",
            "我睡了",
            "先睡了",
            "我去忙了",
            "先不说了",
            "先挂了",
            "改天聊"
        )

        return signals.any {
            text.contains(it)
        }
    }

    private fun isExplicitContinueRequest(
        text: String
    ): Boolean {
        val signals = listOf(
            "继续说",
            "继续讲",
            "接着说",
            "接着讲",
            "然后呢",
            "后来呢",
            "多说点",
            "多聊点",
            "你说吧",
            "你继续",
            "再说点",
            "讲下去",
            "我听着",
            "你多讲一会",
            "陪我聊会儿",
            "你随便说"
        )

        return signals.any {
            text.contains(it)
        }
    }

    private fun parseJson(
        raw: String
    ): JsonObject? {
        val clean = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        runCatching {
            JsonParser.parseString(
                clean
            ).asJsonObject
        }.getOrNull()?.let {
            return it
        }

        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')

        if (
            start >= 0 &&
            end > start
        ) {
            return runCatching {
                JsonParser.parseString(
                    clean.substring(
                        start,
                        end + 1
                    )
                ).asJsonObject
            }.getOrNull()
        }

        return null
    }
}

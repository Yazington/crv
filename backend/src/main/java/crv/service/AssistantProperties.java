package crv.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The assistant's runtime settings. Defaults are the values measured in docs/data-and-search.md, "Runtime settings".
 *
 * @param model the Fireworks model id; passed on every call, because per-call options replace the defaults
 * @param agentReasoningEffort DeepSeek thinking for agent turns: picking tools and writing the reply
 * @param judgeReasoningEffort DeepSeek thinking for judging soft constraints and checking photos
 * @param maxToolCalls tool calls allowed per shopper turn (AC13.1)
 * @param shortlist products whose soft constraints are judged (AC3.5)
 * @param photoChecks top products whose photo is checked when a colour is asked for (AC3.5)
 * @param results products returned per search
 * @param relevanceWeight ranking weight of the full-text match
 * @param qualityWeight ranking weight of the listing's quality score
 * @param popularityWeight ranking weight of popularity (rating times dampened review count)
 * @param historyTurns earlier shopper turns kept in the conversation sent to the model
 */
@ConfigurationProperties("crv.assistant")
public record AssistantProperties(
        @DefaultValue("accounts/fireworks/models/deepseek-v4p1-flash")
        String model,

        @DefaultValue("low") String agentReasoningEffort,
        @DefaultValue("none") String judgeReasoningEffort,
        @DefaultValue("5") int maxToolCalls,
        @DefaultValue("30") int shortlist,
        @DefaultValue("10") int photoChecks,
        @DefaultValue("10") int results,
        @DefaultValue("1.0") double relevanceWeight,
        @DefaultValue("0.3") double qualityWeight,
        @DefaultValue("0.3") double popularityWeight,
        @DefaultValue("6") int historyTurns) {}

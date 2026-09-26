package io.matedata.harness;

import io.matedata.semantic.QueryPlan;
import io.matedata.semantic.SemanticModel;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Natural-language agent that reads the authorized semantic vocabulary, asks the platform to run
 * one governed query and interprets its rows. It never writes SQL or reaches data directly.
 */
public interface QueryAgent {
  /**
   * One question in a conversation.
   *
   * @param model the semantic model already restricted to the user's grant
   * @param sessionKey stable for follow-ups that share the same authorization scope
   */
  record Turn(
      String question, SemanticModel model, String userId, String runId, String sessionKey) {}

  /**
   * Rows returned by the platform after authorization, compilation and SQL verification.
   *
   * @param rowScoped the user's row-level permissions narrowed these rows; the filter values
   *     themselves are never disclosed
   */
  record Rows(List<String> columns, List<Map<String, Object>> rows, boolean rowScoped) {
    public Rows(List<String> columns, List<Map<String, Object>> rows) {
      this(columns, rows, false);
    }
  }

  /** The only data access handed to the agent; implemented by the application service. */
  @FunctionalInterface
  interface GovernedQuery {
    Rows run(QueryPlan plan);
  }

  /** The agent's final reply, excluding reasoning; may be blank when the model gave none. */
  record Reply(String answer) {}

  Reply answer(Turn turn, GovernedQuery query, Consumer<ExecutionStep> observer);
}

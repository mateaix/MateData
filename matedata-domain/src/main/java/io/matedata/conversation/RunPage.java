package io.matedata.conversation;

import java.util.List;

public record RunPage(List<QueryRun> items, Integer nextOffset) {
  public RunPage {
    items = List.copyOf(items);
  }
}

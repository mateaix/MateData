package io.matedata.conversation;
import java.util.*;
public record QueryRun(String id,String conversationId,String question,String datasetId,String mode,String status,String sql,
                       List<String> columns,List<Map<String,Object>> rows,int rowCount,long durationMs,String createdAt,
                       String answer,String error,List<Step> steps) {
    public record Step(String name,String status,String detail,long durationMs){}
    public QueryRun {columns=List.copyOf(columns);rows=List.copyOf(rows);steps=List.copyOf(steps);}
}

package io.matedata.semantic;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;

/** Fail-closed: AST validation plus exact equivalence to our trusted semantic compiler. */
public final class SqlGuard {
  public void verify(CompiledQuery query, SemanticModel model, QueryPlan plan) {
    var expected = new SemanticCompiler().compile(model, plan);
    if (!expected.equals(query)) throw new IllegalArgumentException("SQL 与已授权语义计划不匹配");
    try {
      var statements = CCJSqlParserUtil.parseStatements(query.sql());
      if (statements.size() != 1
          || !(statements.get(0) instanceof PlainSelect select)
          || select.getJoins() != null
          || select.getIntoTables() != null) throw new IllegalArgumentException("仅允许受控的单表只读聚合查询");
    } catch (net.sf.jsqlparser.JSQLParserException e) {
      throw new IllegalArgumentException("SQL 校验失败", e);
    }
  }
}

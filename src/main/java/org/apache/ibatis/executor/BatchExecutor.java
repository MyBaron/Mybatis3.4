/**
 *    Copyright 2009-2017 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.executor;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * 执行update（没有select，JDBC批处理不支持select），将所有sql都添加到批处理中（addBatch()），等待统一执行（executeBatch()），
 * 它缓存了多个Statement对象，每个Statement对象都是addBatch()完毕后，等待逐一执行executeBatch()批处理的；
 * BatchExecutor相当于维护了多个桶，每个桶里都装了很多属于自己的SQL，
 * 就像苹果蓝里装了很多苹果，番茄蓝里装了很多番茄，最后，再统一倒进仓库。（可以是Statement或PrepareStatement对象）
 * @author Jeff Butler
 */
public class BatchExecutor extends BaseExecutor {

  public static final int BATCH_UPDATE_RETURN_VALUE = Integer.MIN_VALUE + 1002;

  /**
   *  缓存多个Statement对象，每个Statement都是addBatch()后，等待执行
   */
  private final List<Statement> statementList = new ArrayList<Statement>();
  /**
   * 对应的结果集（主要保存了update结果的count数量）
   */
  private final List<BatchResult> batchResultList = new ArrayList<BatchResult>();
  /**
   * 当前保存的sql，即上次执行的sql
   */
  private String currentSql;
  private MappedStatement currentStatement;

  public BatchExecutor(Configuration configuration, Transaction transaction) {
    super(configuration, transaction);
  }

  @Override
  public int doUpdate(MappedStatement ms, Object parameterObject) throws SQLException {
    final Configuration configuration = ms.getConfiguration();
    // <1> 创建 StatementHandler 对象
    final StatementHandler handler = configuration.newStatementHandler(this, ms, parameterObject, RowBounds.DEFAULT, null, null);
    final BoundSql boundSql = handler.getBoundSql();
    final String sql = boundSql.getSql();
    final Statement stmt;
    // <2> 如果匹配最后一次 currentSql 和 currentStatement ，则聚合到 BatchResult 中
    // 使用可重用的 Statement 对象，并进行初始化
    if (sql.equals(currentSql) && ms.equals(currentStatement)) {
      // <2.1> 获得最后一次的 Statement 对象
      int last = statementList.size() - 1;
      stmt = statementList.get(last);
      // <2.2> 设置事务超时时间
      applyTransactionTimeout(stmt);
      // <2.3> 设置 SQL 上的参数，例如 PrepareStatement 对象上的占位符
      //fix Issues 322
      handler.parameterize(stmt);
      // <2.4> 获得最后一次的 BatchResult 对象，并添加参数到其中
      BatchResult batchResult = batchResultList.get(last);
      batchResult.addParameterObject(parameterObject);

      // <3> 如果不匹配最后一次 currentSql 和 currentStatement ，则新建 BatchResult 对象
    } else {
      Connection connection = getConnection(ms.getStatementLog());
      stmt = handler.prepare(connection, transaction.getTimeout());
      //fix Issues 322
      handler.parameterize(stmt);
      currentSql = sql;
      currentStatement = ms;
      statementList.add(stmt);
      // <3.6> 创建 BatchResult 对象，并添加到 batchResultList 中
      batchResultList.add(new BatchResult(ms, sql, parameterObject));
    }
  // handler.parameterize(stmt);
    handler.batch(stmt);
    return BATCH_UPDATE_RETURN_VALUE;
  }

  @Override
  public <E> List<E> doQuery(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
      throws SQLException {
    Statement stmt = null;
    try {
      flushStatements();
      Configuration configuration = ms.getConfiguration();
      StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameterObject, rowBounds, resultHandler, boundSql);
      Connection connection = getConnection(ms.getStatementLog());
      stmt = handler.prepare(connection, transaction.getTimeout());
      handler.parameterize(stmt);
      return handler.<E>query(stmt, resultHandler);
    } finally {
      closeStatement(stmt);
    }
  }

  @Override
  protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
    flushStatements();
    Configuration configuration = ms.getConfiguration();
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
    Connection connection = getConnection(ms.getStatementLog());
    Statement stmt = handler.prepare(connection, transaction.getTimeout());
    handler.parameterize(stmt);
    return handler.<E>queryCursor(stmt);
  }

  @Override
  public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
    try {
      List<BatchResult> results = new ArrayList<BatchResult>();
      if (isRollback) {
        return Collections.emptyList();
      }
      // <2> 遍历 statementList 和 batchResultList 数组，逐个提交批处理
      for (int i = 0, n = statementList.size(); i < n; i++) {
        // <2.1> 获得 Statement 和 BatchResult 对象
        Statement stmt = statementList.get(i);
        applyTransactionTimeout(stmt);
        BatchResult batchResult = batchResultList.get(i);
        try {
          // <2.2> 批量执行
          batchResult.setUpdateCounts(stmt.executeBatch());
          // <2.3> 处理主键生成
          MappedStatement ms = batchResult.getMappedStatement();
          List<Object> parameterObjects = batchResult.getParameterObjects();
          KeyGenerator keyGenerator = ms.getKeyGenerator();
          if (Jdbc3KeyGenerator.class.equals(keyGenerator.getClass())) {
            Jdbc3KeyGenerator jdbc3KeyGenerator = (Jdbc3KeyGenerator) keyGenerator;
            jdbc3KeyGenerator.processBatch(ms, stmt, parameterObjects);
          } else if (!NoKeyGenerator.class.equals(keyGenerator.getClass())) { //issue #141
            for (Object parameter : parameterObjects) {
              keyGenerator.processAfter(this, ms, stmt, parameter);
            }
          }
          // Close statement to close cursor #1109
          // <2.4> 关闭 Statement 对象
          closeStatement(stmt);
        } catch (BatchUpdateException e) {
          StringBuilder message = new StringBuilder();
          message.append(batchResult.getMappedStatement().getId())
              .append(" (batch index #")
              .append(i + 1)
              .append(")")
              .append(" failed.");
          if (i > 0) {
            message.append(" ")
                .append(i)
                .append(" prior sub executor(s) completed successfully, but will be rolled back.");
          }
          throw new BatchExecutorException(message.toString(), e, results, batchResult);
        }
        // <2.5> 添加到结果集
        results.add(batchResult);
      }
      return results;
    } finally {
      // <3.1> 关闭 Statement 们
      for (Statement stmt : statementList) {
        closeStatement(stmt);
      }
      // <3.2> 置空 currentSql、statementList、batchResultList 属性
      currentSql = null;
      statementList.clear();
      batchResultList.clear();
    }
  }

}

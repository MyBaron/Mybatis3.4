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
package org.apache.ibatis.scripting.xmltags;

import java.util.Map;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class DynamicSqlSource implements SqlSource {

  private final Configuration configuration;
  private final SqlNode rootSqlNode;

  public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
    this.configuration = configuration;
    this.rootSqlNode = rootSqlNode;
  }

  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    /**
     * 使用场景？ 创建DynamicContext对象 处理参数
     * 可以将其看做 结果容器，每个阶段的处理结果都会存储在DynamicContext
     * 存储SQL以及参数
     * 每个 SQL 片段解析完成后，都会将解析结果存入 DynamicContext 中
     */
    DynamicContext context = new DynamicContext(configuration, parameterObject);
    /**
     *   SqlNode的实现类时MixedSqlNode
     *   MixedSqlNode 内部维护了一个 SqlNode 集合，用于存储各种各样的 SqlNode
     *   此处会处理DynamicContext对象的sql中有${},if,while 等等的占位符，会将参数替换占位符
     */
    rootSqlNode.apply(context);
    //创建SqlSourceBuilder对象，用于下文处理#{xxx} 占位符
    SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
    Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
    /*
     * 构建 StaticSqlSource，在此过程中将 sql 语句中的占位符 #{} 替换为问号 ?，
     * 并为每个占位符构建相应的 ParameterMapping
     *
     *  ParameterMapping是什么？
     *  每个 #{xxx} 占位符都会被解析成相应的 ParameterMapping 对象
     */
    SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());
    // 将 DynamicContext 的 ContextMap 中的内容拷贝到 BoundSql 中
    BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
    for (Map.Entry<String, Object> entry : context.getBindings().entrySet()) {
      boundSql.setAdditionalParameter(entry.getKey(), entry.getValue());
    }
    return boundSql;
  }

}

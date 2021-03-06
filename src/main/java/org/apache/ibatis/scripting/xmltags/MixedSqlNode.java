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

import java.util.List;

/**
 * @author Clinton Begin
 */
public class MixedSqlNode implements SqlNode {

  /**
   * MixedSqlNode内部管理了SqlNode的实现类
   *
   * ChooseSqlNode
   * ForEachSqlNode
   * IfSqlNode
   * StaticTextSqlNode
   * TextSqlNode
   * TrimSqlNode
   * VarDeclSqlNode
   * WhereSqlNode
   * SetSqlNode
   *
   * WhereSqlNode 和 SetSqlNode 都是基于 TrimSqlNode 实现的
   */
  private final List<SqlNode> contents;



  public MixedSqlNode(List<SqlNode> contents) {
    this.contents = contents;
  }

  @Override
  public boolean apply(DynamicContext context) {
    /**
     * 这种设计模式不错
     * 以后扩展需要增加处理方法，只需要继承SqlNode类重写apply方法就可以支持
     */
    for (SqlNode sqlNode : contents) {
      sqlNode.apply(context);
    }
    return true;
  }
}

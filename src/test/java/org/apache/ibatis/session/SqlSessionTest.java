/**
 *    Copyright 2009-2016 the original author or authors.
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
package org.apache.ibatis.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javassist.util.proxy.Proxy;

import org.apache.ibatis.BaseDataTest;
import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.domain.blog.Author;
import org.apache.ibatis.domain.blog.Blog;
import org.apache.ibatis.domain.blog.Comment;
import org.apache.ibatis.domain.blog.DraftPost;
import org.apache.ibatis.domain.blog.ImmutableAuthor;
import org.apache.ibatis.domain.blog.Post;
import org.apache.ibatis.domain.blog.Section;
import org.apache.ibatis.domain.blog.Tag;
import org.apache.ibatis.domain.blog.mappers.AuthorMapper;
import org.apache.ibatis.domain.blog.mappers.AuthorMapperWithMultipleHandlers;
import org.apache.ibatis.domain.blog.mappers.AuthorMapperWithRowBounds;
import org.apache.ibatis.domain.blog.mappers.BlogMapper;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.apache.velocity.util.ArrayListWrapper;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class SqlSessionTest extends BaseDataTest {
  private static SqlSessionFactory sqlMapper;

  @BeforeClass
  public static void setup() throws Exception {
    createBlogDataSource();
    final String resource = "org/apache/ibatis/builder/MapperConfig.xml";
    final Reader reader = Resources.getResourceAsReader(resource);
    sqlMapper = new SqlSessionFactoryBuilder().build(reader);
  }

  @Test
  public void shouldResolveBothSimpleNameAndFullyQualifiedName() {
    /**
     * 测试Cache可以根据全限定类名或者类型来获取到缓存
     * 原理：
     * 存储缓存对象的数据结构是StrictMap。
     * 在put源码中，会存储2个实体或者1个实体：（全限定路径作为key和类名作为key）
     * 1.如果传入的key包含有.，那么会将key按.切割，只用类名作为key。
     * 2.会以全限定名作为key存储
     * 所以就算用全限定类名，内部也只是用类名作为key
     */
    Configuration c = new Configuration();
    final String fullName = "com.mycache.MyCache";
    final String shortName = "MyCache";
    final PerpetualCache cache = new PerpetualCache(fullName);
    c.addCache(cache);
    assertEquals(cache, c.getCache(fullName));
    assertEquals(cache, c.getCache(shortName));
  }

  @Test
  public void shouldFailOverToMostApplicableSimpleName() {
    /**
     * 这里会抛出Caches collection does not contain value for unknown.namespace.MyCache的异常
     * 因为在caches的Map中，没有找到unknown.namespace.MyCache对应的值，所以就会抛出错误
     */
    Configuration c = new Configuration();
    final String fullName = "com.mycache.MyCache";
    final String invalidName = "unknown.namespace.MyCache";
    final PerpetualCache cache = new PerpetualCache(fullName);
    c.addCache(cache);
    assertEquals(cache, c.getCache(fullName));
    assertEquals(cache, c.getCache(invalidName));
  }

  @Test
  public  <V> void shouldSucceedWhenFullyQualifiedButFailDueToAmbiguity() {
    Configuration c = new Configuration();
    /**
     * 由上面shouldResolveBothSimpleNameAndFullyQualifiedName的Test中可以知道，caches其实怎么都会保存以类名作为key的实体，
     * 那么就会出现一种情况，两个类名一样，但是全限定名称却不一样，这种情况下会怎么办呢？
     * 在这种情况下，在put()源码中，第一次会存储成功，第二次存储时会判断key的值是否存在，如果存在则会用Ambiguity对象作为value。
     * 此时并不会抛出异常，为什么？因为业务中如果用全限定名称来获取缓存，其实是可以的，并不需要抛出错误。
     * 如果想通过类名来获取缓存，此时在get()方法中会抛出异常
     */

    final String name1 = "com.mycache.MyCache";
    final PerpetualCache cache1 = new PerpetualCache(name1);
    c.addCache(cache1);

    final String name2 = "com.other.MyCache";
    final PerpetualCache cache2 = new PerpetualCache(name2);
    c.addCache(cache2);

    final String shortName = "MyCache";

    assertEquals(cache1, c.getCache(name1));
    assertEquals(cache2, c.getCache(name2));

    try {
      c.getCache(shortName);
      fail("Exception expected.");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("ambiguous"));
    }

  }

  @Test
  public void shouldFailToAddDueToNameConflict() {
    /**
     * 重复存储的时候也会抛出异常
     */
    Configuration c = new Configuration();
    final String fullName = "com.mycache.MyCache";
    final PerpetualCache cache = new PerpetualCache(fullName);
    try {
      c.addCache(cache);
      c.addCache(cache);
      fail("Exception expected.");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("already contains value"));
    }
  }

  @Test
  public void shouldOpenAndClose() throws Exception {
    /**
     * TransactionIsolationLevel 事务等级
     * NONE,
     * READ_COMMITTED 提交读,
     * READ_UNCOMMITTED 不提交读,
     * REPEATABLE_READ 可重复读,
     * SERIALIZABLE;
     *
     * SqlSession 完全包含了面向数据库执行 SQL 命令所需的所有方法。可以通过 SqlSession 实例来直接执行已映射的 SQL 语句
     * 每个线程都应该有它自己的 SqlSession 实例。SqlSession 的实例不是线程安全的，因此是不能被共享的，所以它的最佳的作用域是请求或方法作用域。
     * 线程不安全在哪里？
     * 每当用SqlSession生成Mapper代理类时，代理类里面是存储该SqlSession对象的，
     * 也就是说所有由这个SqlSession对象创建的代理类都是共同访问一个SqlSession对象
     */
    SqlSession session = sqlMapper.openSession(TransactionIsolationLevel.SERIALIZABLE);
    session.close();
  }

  @Test
  public void shouldCommitAnUnUsedSqlSession() throws Exception {
    SqlSession session = sqlMapper.openSession(TransactionIsolationLevel.SERIALIZABLE);
    session.commit(true);
    session.close();
  }

  @Test
  public void shouldRollbackAnUnUsedSqlSession() throws Exception {
    SqlSession session = sqlMapper.openSession(TransactionIsolationLevel.SERIALIZABLE);
    session.rollback(true);
    session.close();
  }

  @Test
  public void shouldSelectAllAuthors() throws Exception {
    SqlSession session = sqlMapper.openSession(TransactionIsolationLevel.SERIALIZABLE);
    try {
      List<Author> authors = session.selectList("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAllAuthors");
      assertEquals(2, authors.size());
    } finally {
      session.close();
    }
  }

  @Test(expected=TooManyResultsException.class)
  public void shouldFailWithTooManyResultsException() throws Exception {
    SqlSession session = sqlMapper.openSession(TransactionIsolationLevel.SERIALIZABLE);
    try {
      //查询数据
      /**
       * selectOne 内部也是调用selectList()方法，返回一个集合
       * 如果返回的集合的size>1，会抛出异常
       */
      session.selectOne("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAllAuthors");
    } finally {
      session.close();
    }
  }
  
  @Test
  public void shouldSelectAllAuthorsAsMap() throws Exception {
    SqlSession session = sqlMapper.openSession(TransactionIsolationLevel.SERIALIZABLE);
    try {
      /**
       *  内部也是调用selectList()方法，返回一个集合。
       *  然后通过reflection将集合的对象的mapKey的值获取，并作为map的key
       */
      final Map<Integer,Author> authors = session.selectMap("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAllAuthors", "id");
      assertEquals(2, authors.size());
      for(Map.Entry<Integer,Author> authorEntry : authors.entrySet()) {
        assertEquals(authorEntry.getKey(), (Integer) authorEntry.getValue().getId());
      }
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldSelectCountOfPosts() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      /**
       *  内部也是调用selectList()方法，返回一个集合。
       *  该集合只有一个值
       */
      Integer count = session.selectOne("org.apache.ibatis.domain.blog.mappers.BlogMapper.selectCountOfPosts");
      assertEquals(5, count.intValue());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldEnsureThatBothEarlyAndLateResolutionOfNesteDiscriminatorsResolesToUseNestedResultSetHandler() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      /**
       * 测试是否成功加载了<ResultMap></ResultMap>
       * 判断是否存在嵌套ResultMap
       */
      Configuration configuration = sqlMapper.getConfiguration();
      assertTrue(configuration.getResultMap("org.apache.ibatis.domain.blog.mappers.BlogMapper.earlyNestedDiscriminatorPost").hasNestedResultMaps());
      assertTrue(configuration.getResultMap("org.apache.ibatis.domain.blog.mappers.BlogMapper.lateNestedDiscriminatorPost").hasNestedResultMaps());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldSelectOneAuthor() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      /**
       * 此处测试<ResultMap></ResultMap>映射的Author对象
       */
      Author author = session.selectOne(
          "org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthor", new Author(101));
      assertEquals(101, author.getId());
      assertEquals(Section.NEWS, author.getFavouriteSection());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldSelectOneAuthorAsList() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {

      /**
       * 虽然sql是查出一个对象，因为selectOne内部其实是调用selectList()方法查询的，所以直接调用selectList()也是可以的
       */
      List<Author> authors = session.selectList(
          "org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthor", new Author(101));
      assertEquals(101, authors.get(0).getId());
      assertEquals(Section.NEWS, authors.get(0).getFavouriteSection());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldSelectOneImmutableAuthor() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      /**
       * 测试<constructor></constructor>
       * 会在DefaultResultSerHandler#createResultObject()方法中初始化对象
       */

      ImmutableAuthor author = session.selectOne(
          "org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectImmutableAuthor", new Author(101));
      assertEquals(101, author.getId());
      assertEquals(Section.NEWS, author.getFavouriteSection());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldSelectOneAuthorWithInlineParams() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      /**
       * 测试可以通过内部的参数作为查询条件
       *
       * mybatis是怎么解析传参对象的值对应sql的占位符呢？
       * 会在SimpleExecutor$prepareStatement方法中，进行sql预处理。
       * 如果传入的参数是对象，会通过反射并将数据存储在MetaObject对象中
       *
       *
       * todo 区别？使用场景？
       * DefaultSqlSession
       * SqlSessionManager
       */
      Author author = session.selectOne(
          "org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthorWithInlineParams", new Author(101));
      assertEquals(101, author.getId());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldInsertAuthor() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      /**
       * 此处测试的是插入语句的执行顺序
       * 跟select相似
       * 更新/插入都是调用同一个方法，
       * 更新/插入都会刷新二级缓存和一级缓存
       */
      Author expected = new Author(500, "cbegin", "******", "cbegin@somewhere.com", "Something...", null);
      int updates = session.insert("org.apache.ibatis.domain.blog.mappers.AuthorMapper.insertAuthor", expected);
      assertEquals(1, updates);
      Author actual = session.selectOne("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthor", new Author(500));
      assertNotNull(actual);
      assertEquals(expected.getId(), actual.getId());
      assertEquals(expected.getUsername(), actual.getUsername());
      assertEquals(expected.getPassword(), actual.getPassword());
      assertEquals(expected.getEmail(), actual.getEmail());
      assertEquals(expected.getBio(), actual.getBio());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldUpdateAuthorImplicitRollback() throws Exception {

    /**
     * 测试当AutoCommit设置成false的时候并且dirty为true。SqlSession$close会进行回滚
     * 回滚流程如下
     * 1. CachingExecutor$close方法
     * 2. BaseExecutor$close方法 会清除本地缓存
     * 3. 调用事务的回滚例如JdbcTransaction$rollback
     * 4. 调用DefaultSqlSession$closeCursors
     */
    SqlSession session = sqlMapper.openSession();
    Author original;
    Author updated;
    try {
      original = session.selectOne("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthor", 101);
      original.setEmail("new@email.com");
      int updates = session.update("org.apache.ibatis.domain.blog.mappers.AuthorMapper.updateAuthor", original);
      assertEquals(1, updates);
      updated = session.selectOne("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthor", 101);
      assertEquals(original.getEmail(), updated.getEmail());
    } finally {
      session.close();
    }
    try {
      session = sqlMapper.openSession();
      updated = session.selectOne("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthor", 101);
      assertEquals("jim@ibatis.apache.org", updated.getEmail());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldUpdateAuthorCommit() throws Exception {

    /**
     * 测试主动提交后，不会进行数据回滚
     * 提交流程：
     * 1. 清除本地缓存
     * 2. 调用事务的提交
     *
     * 在SqlSession有个dirty参数，记录是否是脏数据，如果主动调用commit方法，提交完成后会将其dirty设置成false
     *
     */
    SqlSession session = sqlMapper.openSession();
    Author original;
    Author updated;
    try {
      original = session.selectOne("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthor", 101);
      original.setEmail("new@email.com");
      int updates = session.update("org.apache.ibatis.domain.blog.mappers.AuthorMapper.updateAuthor", original);
      assertEquals(1, updates);
      updated = session.selectOne("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthor", 101);
      assertEquals(original.getEmail(), updated.getEmail());
      //自动提交
      session.commit();
    } finally {
      session.close();
    }
    try {
      session = sqlMapper.openSession();
      updated = session.selectOne("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthor", 101);
      assertEquals(original.getEmail(), updated.getEmail());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldUpdateAuthorIfNecessary() throws Exception {
    /**
     * 此处是测试 为null的列不会更新
     *
     * 在insert/update 流程中，在创建BaseStatementHandler对象是，会从Configuration对象中获取MappedStatement对象
     * MappedStatement$getBoundSql方法处理sql，内部会通过DynamicSqlSource 会调用 SqlNode处理不同的标签,例如<if></if>
     *
     */
    SqlSession session = sqlMapper.openSession();
    Author original;
    Author updated;
    try {
      original = session.selectOne("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthor", 101);
      original.setEmail("new@email.com");
      original.setBio(null);
      int updates = session.update("org.apache.ibatis.domain.blog.mappers.AuthorMapper.updateAuthorIfNecessary", original);
      assertEquals(1, updates);
      updated = session.selectOne("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthor", 101);
      assertEquals(original.getEmail(), updated.getEmail());
      session.commit();
    } finally {
      session.close();
    }
    try {
      session = sqlMapper.openSession();
      updated = session.selectOne("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthor", 101);
      assertEquals(original.getEmail(), updated.getEmail());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldDeleteAuthor() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      final int id = 102;

      List<Author> authors = session.selectList("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthor", id);
      assertEquals(1, authors.size());

      int updates = session.delete("org.apache.ibatis.domain.blog.mappers.AuthorMapper.deleteAuthor", id);
      assertEquals(1, updates);

      authors = session.selectList("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthor", id);
      assertEquals(0, authors.size());

      session.rollback();
      authors = session.selectList("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthor", id);
      assertEquals(1, authors.size());

    } finally {
      session.close();
    }
  }

  @Test
  public void shouldSelectBlogWithPostsAndAuthorUsingSubSelects() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      Blog blog = session.selectOne("org.apache.ibatis.domain.blog.mappers.BlogMapper.selectBlogWithPostsUsingSubSelect", 1);
      assertEquals("Jim Business", blog.getTitle());
      assertEquals(2, blog.getPosts().size());
      assertEquals("Corn nuts", blog.getPosts().get(0).getSubject());
      assertEquals(101, blog.getAuthor().getId());
      assertEquals("jim", blog.getAuthor().getUsername());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldSelectBlogWithPostsAndAuthorUsingSubSelectsLazily() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      Blog blog = session.selectOne("org.apache.ibatis.domain.blog.mappers.BlogMapper.selectBlogWithPostsUsingSubSelectLazily", 1);
      Assert.assertTrue(blog instanceof Proxy);
      assertEquals("Jim Business", blog.getTitle());
      assertEquals(2, blog.getPosts().size());
      assertEquals("Corn nuts", blog.getPosts().get(0).getSubject());
      assertEquals(101, blog.getAuthor().getId());
      assertEquals("jim", blog.getAuthor().getUsername());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldSelectBlogWithPostsAndAuthorUsingJoin() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      Blog blog = session.selectOne("org.apache.ibatis.domain.blog.mappers.BlogMapper.selectBlogJoinedWithPostsAndAuthor", 1);
      assertEquals("Jim Business", blog.getTitle());

      final Author author = blog.getAuthor();
      assertEquals(101, author.getId());
      assertEquals("jim", author.getUsername());

      final List<Post> posts = blog.getPosts();
      assertEquals(2, posts.size());

      final Post post = blog.getPosts().get(0);
      assertEquals(1, post.getId());
      assertEquals("Corn nuts", post.getSubject());

      final List<Comment> comments = post.getComments();
      assertEquals(2, comments.size());

      final List<Tag> tags = post.getTags();
      assertEquals(3, tags.size());

      final Comment comment = comments.get(0);
      assertEquals(1, comment.getId());

      assertEquals(DraftPost.class, blog.getPosts().get(0).getClass());
      assertEquals(Post.class, blog.getPosts().get(1).getClass());

    } finally {
      session.close();
    }
  }

  @Test
  public void shouldSelectNestedBlogWithPostsAndAuthorUsingJoin() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      Blog blog = session.selectOne("org.apache.ibatis.domain.blog.mappers.NestedBlogMapper.selectBlogJoinedWithPostsAndAuthor", 1);
      assertEquals("Jim Business", blog.getTitle());

      final Author author = blog.getAuthor();
      assertEquals(101, author.getId());
      assertEquals("jim", author.getUsername());

      final List<Post> posts = blog.getPosts();
      assertEquals(2, posts.size());

      final Post post = blog.getPosts().get(0);
      assertEquals(1, post.getId());
      assertEquals("Corn nuts", post.getSubject());

      final List<Comment> comments = post.getComments();
      assertEquals(2, comments.size());

      final List<Tag> tags = post.getTags();
      assertEquals(3, tags.size());

      final Comment comment = comments.get(0);
      assertEquals(1, comment.getId());

      assertEquals(DraftPost.class, blog.getPosts().get(0).getClass());
      assertEquals(Post.class, blog.getPosts().get(1).getClass());

    } finally {
      session.close();
    }
  }

  @Test
  public void shouldThrowExceptionIfMappedStatementDoesNotExist() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      session.selectList("ThisStatementDoesNotExist");
      fail("Expected exception to be thrown due to statement that does not exist.");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("does not contain value for ThisStatementDoesNotExist"));
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldThrowExceptionIfTryingToAddStatementWithSameName() throws Exception {
    Configuration config = sqlMapper.getConfiguration();
    try {
      config.addMappedStatement(config.getMappedStatement("org.apache.ibatis.domain.blog.mappers.BlogMapper.selectBlogWithPostsUsingSubSelect"));
      fail("Expected exception to be thrown due to statement that already exists.");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("already contains value for org.apache.ibatis.domain.blog.mappers.BlogMapper.selectBlogWithPostsUsingSubSelect"));
    }
  }

  @Test
  public void shouldCacheAllAuthors() throws Exception {
    int first = -1;
    int second = -1;
    SqlSession session = sqlMapper.openSession();
    try {
      List<Author> authors = session.selectList("org.apache.ibatis.builder.CachedAuthorMapper.selectAllAuthors");
      first = System.identityHashCode(authors);
      session.commit(); // commit should not be required for read/only activity.
    } finally {
      session.close();
    }
    session = sqlMapper.openSession();
    try {
      List<Author> authors = session.selectList("org.apache.ibatis.builder.CachedAuthorMapper.selectAllAuthors");
      second = System.identityHashCode(authors);
    } finally {
      session.close();
    }
    assertEquals(first, second);
  }

  @Test
  public void shouldNotCacheAllAuthors() throws Exception {
    int first = -1;
    int second = -1;
    SqlSession session = sqlMapper.openSession();
    try {
      List<Author> authors = session.selectList("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAllAuthors");
      first = System.identityHashCode(authors);
    } finally {
      session.close();
    }
    session = sqlMapper.openSession();
    try {
      List<Author> authors = session.selectList("org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAllAuthors");
      second = System.identityHashCode(authors);
    } finally {
      session.close();
    }
    assertTrue(first != second);
  }

  @Test
  public void shouldSelectAuthorsUsingMapperClass() {
    SqlSession session = sqlMapper.openSession();
    try {
      AuthorMapper mapper = session.getMapper(AuthorMapper.class);
      List<Author> authors = mapper.selectAllAuthors();
      assertEquals(2, authors.size());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldExecuteSelectOneAuthorUsingMapperClass() {
    SqlSession session = sqlMapper.openSession();
    try {
      AuthorMapper mapper = session.getMapper(AuthorMapper.class);
      Author author = mapper.selectAuthor(101);
      assertEquals(101, author.getId());
    } finally {
      session.close();
    }
  }


  @Test
  public void shouldExecuteSelectOneAuthorUsingMapperClassThatReturnsALinedHashMap() {
    SqlSession session = sqlMapper.openSession();
    try {
      AuthorMapper mapper = session.getMapper(AuthorMapper.class);
      LinkedHashMap<String, Object> author = mapper.selectAuthorLinkedHashMap(101);
      assertEquals(101, author.get("ID"));
    } finally {
      session.close();
    }
  }
  
  @Test
  public void shouldExecuteSelectAllAuthorsUsingMapperClassThatReturnsSet() {
    SqlSession session = sqlMapper.openSession();
    try {
      AuthorMapper mapper = session.getMapper(AuthorMapper.class);
      Collection<Author> authors = mapper.selectAllAuthorsSet();
      assertEquals(2, authors.size());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldExecuteSelectAllAuthorsUsingMapperClassThatReturnsVector() {
    SqlSession session = sqlMapper.openSession();
    try {
      AuthorMapper mapper = session.getMapper(AuthorMapper.class);
      Collection<Author> authors = mapper.selectAllAuthorsVector();
      assertEquals(2, authors.size());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldExecuteSelectAllAuthorsUsingMapperClassThatReturnsLinkedList() {
    SqlSession session = sqlMapper.openSession();
    try {
      AuthorMapper mapper = session.getMapper(AuthorMapper.class);
      Collection<Author> authors = mapper.selectAllAuthorsLinkedList();
      assertEquals(2, authors.size());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldExecuteSelectAllAuthorsUsingMapperClassThatReturnsAnArray() {
    SqlSession session = sqlMapper.openSession();
    try {
      AuthorMapper mapper = session.getMapper(AuthorMapper.class);
      Author[] authors = mapper.selectAllAuthorsArray();
      assertEquals(2, authors.length);
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldExecuteSelectOneAuthorUsingMapperClassWithResultHandler() {
    SqlSession session = sqlMapper.openSession();
    try {
      DefaultResultHandler handler = new DefaultResultHandler();
      AuthorMapper mapper = session.getMapper(AuthorMapper.class);
      mapper.selectAuthor(101, handler);
      Author author = (Author) handler.getResultList().get(0);
      assertEquals(101, author.getId());
    } finally {
      session.close();
    }
  }

  @Test(expected=BindingException.class)
  public void shouldFailExecutingAnAnnotatedMapperClassWithResultHandler() {
    SqlSession session = sqlMapper.openSession();
    try {
      DefaultResultHandler handler = new DefaultResultHandler();
      AuthorMapper mapper = session.getMapper(AuthorMapper.class);
      mapper.selectAuthor2(101, handler);
      Author author = (Author) handler.getResultList().get(0);
      assertEquals(101, author.getId());
    } finally {
      session.close();
    }
  }
  
  @Test
  public void shouldSelectAuthorsUsingMapperClassWithResultHandler() {
    SqlSession session = sqlMapper.openSession();
    try {
      DefaultResultHandler handler = new DefaultResultHandler();
      AuthorMapper mapper = session.getMapper(AuthorMapper.class);
      mapper.selectAllAuthors(handler);
      assertEquals(2, handler.getResultList().size());
    } finally {
      session.close();
    }
  }

  @Test(expected = BindingException.class)
  public void shouldFailSelectOneAuthorUsingMapperClassWithTwoResultHandlers() {
    Configuration configuration = new Configuration(sqlMapper.getConfiguration().getEnvironment());
    configuration.addMapper(AuthorMapperWithMultipleHandlers.class);
    SqlSessionFactory sqlMapperWithMultipleHandlers = new DefaultSqlSessionFactory(configuration);
    SqlSession sqlSession = sqlMapperWithMultipleHandlers.openSession();
    try {
      DefaultResultHandler handler1 = new DefaultResultHandler();
      DefaultResultHandler handler2 = new DefaultResultHandler();
      AuthorMapperWithMultipleHandlers mapper = sqlSession.getMapper(AuthorMapperWithMultipleHandlers.class);
      mapper.selectAuthor(101, handler1, handler2);
    } finally {
      sqlSession.close();
    }
  }

  @Test(expected = BindingException.class)
  public void shouldFailSelectOneAuthorUsingMapperClassWithTwoRowBounds() {
    Configuration configuration = new Configuration(sqlMapper.getConfiguration().getEnvironment());
    configuration.addMapper(AuthorMapperWithRowBounds.class);
    SqlSessionFactory sqlMapperWithMultipleHandlers = new DefaultSqlSessionFactory(configuration);
    SqlSession sqlSession = sqlMapperWithMultipleHandlers.openSession();
    try {
      RowBounds bounds1 = new RowBounds(0, 1);
      RowBounds bounds2 = new RowBounds(0, 1);
      AuthorMapperWithRowBounds mapper = sqlSession.getMapper(AuthorMapperWithRowBounds.class);
      mapper.selectAuthor(101, bounds1, bounds2);
    } finally {
      sqlSession.close();
    }
  }

  @Test
  public void shouldInsertAuthorUsingMapperClass() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      AuthorMapper mapper = session.getMapper(AuthorMapper.class);
      Author expected = new Author(500, "cbegin", "******", "cbegin@somewhere.com", "Something...", null);
      mapper.insertAuthor(expected);
      Author actual = mapper.selectAuthor(500);
      assertNotNull(actual);
      assertEquals(expected.getId(), actual.getId());
      assertEquals(expected.getUsername(), actual.getUsername());
      assertEquals(expected.getPassword(), actual.getPassword());
      assertEquals(expected.getEmail(), actual.getEmail());
      assertEquals(expected.getBio(), actual.getBio());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldDeleteAuthorUsingMapperClass() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      AuthorMapper mapper = session.getMapper(AuthorMapper.class);
      int count = mapper.deleteAuthor(101);
      assertEquals(1, count);
      assertNull(mapper.selectAuthor(101));
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldUpdateAuthorUsingMapperClass() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      AuthorMapper mapper = session.getMapper(AuthorMapper.class);
      Author expected = mapper.selectAuthor(101);
      expected.setUsername("NewUsername");
      int count = mapper.updateAuthor(expected);
      assertEquals(1, count);
      Author actual = mapper.selectAuthor(101);
      assertEquals(expected.getUsername(), actual.getUsername());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldSelectAllPostsUsingMapperClass() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      BlogMapper mapper = session.getMapper(BlogMapper.class);
      List<Map> posts = mapper.selectAllPosts();
      assertEquals(5, posts.size());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldLimitResultsUsingMapperClass() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      BlogMapper mapper = session.getMapper(BlogMapper.class);
      List<Map> posts = mapper.selectAllPosts(new RowBounds(0, 2), null);
      assertEquals(2, posts.size());
      assertEquals(1, posts.get(0).get("ID"));
      assertEquals(2, posts.get(1).get("ID"));
    } finally {
      session.close();
    }
  }

  private static class TestResultHandler implements ResultHandler {
    int count = 0;
    @Override
    public void handleResult(ResultContext context) {
      count++;
    }
  }

  @Test
  public void shouldHandleZeroParameters() throws Exception {
    SqlSession session = sqlMapper.openSession();

    try {
      final TestResultHandler resultHandler = new TestResultHandler();
      session.select("org.apache.ibatis.domain.blog.mappers.BlogMapper.selectAllPosts", resultHandler);
      assertEquals(5, resultHandler.count);
    } finally {
      session.close();
    }
  }

  private static class TestResultStopHandler implements ResultHandler {
    int count = 0;
    @Override
    public void handleResult(ResultContext context) {
      count++;
      if (count == 2) context.stop();
    }
  }

  @Test
  public void shouldStopResultHandler() throws Exception {
    SqlSession session = sqlMapper.openSession();

    try {
      final TestResultStopHandler resultHandler = new TestResultStopHandler();
      session.select("org.apache.ibatis.domain.blog.mappers.BlogMapper.selectAllPosts", null, resultHandler);
      assertEquals(2, resultHandler.count);
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldOffsetAndLimitResultsUsingMapperClass() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      BlogMapper mapper = session.getMapper(BlogMapper.class);
      List<Map> posts = mapper.selectAllPosts(new RowBounds(2, 3));
      assertEquals(3, posts.size());
      assertEquals(3, posts.get(0).get("ID"));
      assertEquals(4, posts.get(1).get("ID"));
      assertEquals(5, posts.get(2).get("ID"));
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldFindPostsAllPostsWithDynamicSql() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      List<Post> posts = session.selectList("org.apache.ibatis.domain.blog.mappers.PostMapper.findPost");
      assertEquals(5, posts.size());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldFindPostByIDWithDynamicSql() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      List<Post> posts = session.selectList("org.apache.ibatis.domain.blog.mappers.PostMapper.findPost",
          new HashMap<String, Integer>() {{
            put("id", 1);
          }});
      assertEquals(1, posts.size());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldFindPostsInSetOfIDsWithDynamicSql() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      List<Post> posts = session.selectList("org.apache.ibatis.domain.blog.mappers.PostMapper.findPost",
          new HashMap<String, List<Integer>>() {{
            put("ids", new ArrayList<Integer>() {{
              add(1);
              add(2);
              add(3);
            }});
          }});
      assertEquals(3, posts.size());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldFindPostsWithBlogIdUsingDynamicSql() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      List<Post> posts = session.selectList("org.apache.ibatis.domain.blog.mappers.PostMapper.findPost",
          new HashMap<String, Integer>() {{
            put("blog_id", 1);
          }});
      assertEquals(2, posts.size());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldFindPostsWithAuthorIdUsingDynamicSql() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      List<Post> posts = session.selectList("org.apache.ibatis.domain.blog.mappers.PostMapper.findPost",
          new HashMap<String, Integer>() {{
            put("author_id", 101);
          }});
      assertEquals(3, posts.size());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldFindPostsWithAuthorAndBlogIdUsingDynamicSql() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      List<Post> posts = session.selectList("org.apache.ibatis.domain.blog.mappers.PostMapper.findPost",
          new HashMap<String, Object>() {{
            put("ids", new ArrayList<Integer>() {{
              add(1);
              add(2);
              add(3);
            }});
            put("blog_id", 1);
          }});
      assertEquals(2, posts.size());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldFindPostsInList() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      List<Post> posts = session.selectList("org.apache.ibatis.domain.blog.mappers.PostMapper.selectPostIn",
          new ArrayList<Integer>() {{
            add(1);
            add(3);
            add(5);
          }});
      assertEquals(3, posts.size());
    } finally {
      session.close();
    }
  }

  @Test
  public void shouldFindOddPostsInList() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      List<Post> posts = session.selectList("org.apache.ibatis.domain.blog.mappers.PostMapper.selectOddPostsIn",
          new ArrayList<Integer>() {{
            add(0);
            add(1);
            add(2);
            add(3);
            add(4);
          }});
      // we're getting odd indexes, not odd values, 0 is not odd
      assertEquals(2, posts.size());
      assertEquals(1, posts.get(0).getId());
      assertEquals(3, posts.get(1).getId());
    } finally {
      session.close();
    }
  }


  @Test
  public void shouldSelectOddPostsInKeysList() throws Exception {
    SqlSession session = sqlMapper.openSession();
    try {
      List<Post> posts = session.selectList("org.apache.ibatis.domain.blog.mappers.PostMapper.selectOddPostsInKeysList",
          new HashMap<String, List<Integer>>() {{put("keys",new ArrayList<Integer>() {{
            add(0);
            add(1);
            add(2);
            add(3);
            add(4);
          }});
          }});
      // we're getting odd indexes, not odd values, 0 is not odd
      assertEquals(2, posts.size());
      assertEquals(1, posts.get(0).getId());
      assertEquals(3, posts.get(1).getId());
    } finally {
      session.close();
    }
  }

}

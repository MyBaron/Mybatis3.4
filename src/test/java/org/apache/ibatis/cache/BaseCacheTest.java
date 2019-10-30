/**
 *    Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.cache;

import org.apache.ibatis.cache.decorators.LoggingCache;
import org.apache.ibatis.cache.decorators.ScheduledCache;
import org.apache.ibatis.cache.decorators.SerializedCache;
import org.apache.ibatis.cache.decorators.SynchronizedCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class BaseCacheTest {


  /**
   * 映射语句文件中的所有 select 语句的结果将会被缓存。
   * 映射语句文件中的所有 insert、update 和 delete 语句会刷新缓存。
   * 缓存会使用最近最少使用算法（LRU, Least Recently Used）算法来清除不需要的缓存。
   * 缓存不会定时进行刷新（也就是说，没有刷新间隔）。
   * 缓存会保存列表或对象（无论查询方法返回哪种）的 1024 个引用。
   * 缓存会被视为读/写缓存，这意味着获取到的对象并不是共享的，可以安全地被调用者修改，而不干扰其他调用者或线程所做的潜在修改。
   */

  @Test
  public void shouldDemonstrateEqualsAndHashCodeForVariousCacheTypes() {
    /**
     * PerpetualCache
     *
     * 实现 Cache 接口，永不过期的 Cache 实现类，基于 HashMap 实现类
     */
    PerpetualCache cache = new PerpetualCache("test_cache");
    assertTrue(cache.equals(cache));
    assertTrue(cache.equals(new SynchronizedCache(cache)));
    assertTrue(cache.equals(new SerializedCache(cache)));
    assertTrue(cache.equals(new LoggingCache(cache)));
    assertTrue(cache.equals(new ScheduledCache(cache)));

    assertEquals(cache.hashCode(), new SynchronizedCache(cache).hashCode());
    assertEquals(cache.hashCode(), new SerializedCache(cache).hashCode());
    assertEquals(cache.hashCode(), new LoggingCache(cache).hashCode());
    assertEquals(cache.hashCode(), new ScheduledCache(cache).hashCode());

    Set<Cache> caches = new HashSet<Cache>();
    caches.add(cache);
    caches.add(new SynchronizedCache(cache));
    caches.add(new SerializedCache(cache));
    caches.add(new LoggingCache(cache));
    caches.add(new ScheduledCache(cache));
    assertEquals(1, caches.size());
  }

}
